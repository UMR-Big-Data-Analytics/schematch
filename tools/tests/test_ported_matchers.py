"""REMA, the OmniMatch ablation arms and the learned combiner on small synthetic inputs.

Parity with the cached research outputs is recorded in docs/python-matchers.md and docs/evaluation.md. gensim and
scikit-learn are optional; the tests that need them are skipped explicitly when they are absent.
"""
import csv
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

PROJECT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT / "src/main/resources/python_matchers"))
sys.path.insert(0, str(PROJECT / "tools"))
from schematch_py import rema
import learned_combiner as lc

HAS_GENSIM = importlib.util.find_spec("gensim") is not None
HAS_SKLEARN = importlib.util.find_spec("sklearn") is not None
HAS_PANDAS = importlib.util.find_spec("pandas") is not None
HAS_TORCH = importlib.util.find_spec("torch") is not None


def write_table(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        csv.writer(fh).writerows(rows)


class RemaTest(unittest.TestCase):
    def test_graph_shares_value_nodes_across_relations_and_skips_nulls(self):
        g = rema.build_arc_graph([["a", "b", ""], ["x", "x", "NA"]], [["b", "c"], ["y", "x"]])
        self.assertEqual(g.n_rows_used, (3, 2))
        shared = set(v for c in g.value_nodes[0] for v in c) & set(v for c in g.value_nodes[1] for v in c)
        self.assertEqual(len(shared), 2)                                  # "b" and "x"
        self.assertEqual([len(c) for c in g.value_nodes[0]], [2, 1])      # nulls are not nodes
        self.assertEqual(rema.build_arc_graph([["a", "b", "c"]], [["a"]], row_cap=2).n_rows_used, (2, 1))
        with self.assertRaises(ValueError):
            rema.build_arc_graph([["a"], ["a", "b"]], [["a"]])

    def test_walks_are_seeded_and_cosine_handles_zero_vectors(self):
        g = rema.build_arc_graph([["a", "b"]], [["b", "c"]])
        a = np.vstack(list(rema.walk_chunks(g, 2, 5, seed=3, chunk=4)))
        b = np.vstack(list(rema.walk_chunks(g, 2, 5, seed=3, chunk=4)))
        self.assertTrue(np.array_equal(a, b))
        S = rema.cosine_scores(np.array([[1.0, 0.0], [0.0, 0.0]]), np.array([[1.0, 0.0]]))
        self.assertEqual(S.tolist(), [[1.0], [0.0]])

    def scenario(self, root, target_tables=1):
        sc = root / "D" / "case"
        write_table(sc / "source" / "s.csv", [["id", "name"]] + [[str(i), f"n{i % 5}"] for i in range(30)])
        for t in range(target_tables):
            write_table(sc / "target" / f"t{t}.csv", [["key", "label"]] + [[str(i), f"n{i % 5}"] for i in range(20)])
        return sc

    def test_requires_one_table_per_side(self):
        with tempfile.TemporaryDirectory() as tmp:
            sc = self.scenario(Path(tmp), target_tables=2)
            ctx = {"rt": Path(tmp), "ds": "D", "sc": "case", "rows": ["s.id"], "cols": ["t0.key"]}
            with self.assertRaisesRegex(ValueError, "two relations"):
                rema.rema(ctx, {})

    @unittest.skipUnless(HAS_GENSIM, "gensim is not installed")
    def test_end_to_end_is_deterministic_and_order_independent(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.scenario(Path(tmp))
            ctx = {"rt": Path(tmp), "ds": "D", "sc": "case", "rows": ["s.id", "s.name"], "cols": ["t0.key", "t0.label"]}
            S = rema.rema(ctx, {"dimensions": 16})
            again = rema.rema(dict(ctx, rows=["s.name", "s.id"]), {"dimensions": 16})
            self.assertTrue(np.array_equal(S[::-1], again))
            self.assertGreater(S[1, 1], S[1, 0])                         # shared values carry the match


@unittest.skipUnless(HAS_PANDAS, "pandas is not installed")
class OmniMatchArmsTest(unittest.TestCase):
    def test_unknown_arm_fails_before_loading_vectors(self):
        from schematch_py import omnimatch_release as om
        with self.assertRaisesRegex(ValueError, "unknown OmniMatch arm"):
            om.omnimatch({"rows": ["a.x"], "cols": ["b.y"]}, {"arm": "bogus"})
        self.assertEqual(om.ARMS, ("full", "no_edges", "one_step_edges", "random_forest"))

    def test_edge_counts(self):
        from schematch_py import omnimatch_release as om
        pairs = [(0, 2), (0, 3), (1, 2), (1, 3)]
        feats = [[0.9] * 5, [0.1] * 5, [0.2] * 5, [0.8] * 5]
        counts = om._edge_counts(pairs, feats, 1)
        self.assertEqual(counts[frozenset((0, 2))], 5)
        self.assertNotIn(frozenset((0, 3)), counts)

    @unittest.skipUnless(HAS_TORCH, "PyTorch is not installed")
    def test_gamma_is_chosen_on_training_triplets(self):
        from schematch_py import omnimatch_release as om
        import torch
        counts = {frozenset((0, 2)): 5}
        Z = torch.zeros((4, 2))                                          # embeddings carry nothing
        self.assertGreater(om._edge_gamma(torch, Z, ([0], [2], [3]), counts), 0.0)
        self.assertEqual(om._edge_gamma(torch, None, ([], [], []), counts), 0.0)

    @unittest.skipUnless(HAS_SKLEARN, "scikit-learn is not installed")
    def test_random_forest_arm_scores_join_probability(self):
        from schematch_py import omnimatch_release as om
        tr_pairs = [(i, 100 + i) for i in range(20)]
        tr_feats = [[0.9] * 5 if i % 2 else [0.1] * 5 for i in range(20)]
        joins = [[p for i, p in enumerate(tr_pairs) if i % 2]]
        P = om._random_forest(tr_pairs, tr_feats, joins, [[0.95] * 5, [0.05] * 5], 1, 2, seed=1)
        self.assertEqual(P.shape, (1, 2))
        self.assertGreater(P[0, 0], P[0, 1])
        self.assertTrue(np.array_equal(om._random_forest(tr_pairs, tr_feats, [[]], [[0.5] * 5], 1, 1, 1), np.zeros((1, 1))))


class LearnedCombinerTest(unittest.TestCase):
    def records(self, path, families=("A", "B", "C")):
        rng = np.random.default_rng(0)
        lines = []
        for f in families:
            for s in range(2):
                gt = np.eye(3, dtype=int)
                good = gt * 0.8 + rng.random((3, 3)) * 0.3
                for name, S in (("good", good), ("noise", rng.random((3, 3)))):
                    lines.append({"dataset": f, "scenario": f"s{s}", "stage": "MatchingStepLine1", "matcher": name,
                                  "scores": S.tolist(), "ground_truth": gt.tolist(),
                                  "source_labels": ["t.a", "t.b", "t.c"], "target_labels": ["u.a", "u.b", "u.c"]})
        path.write_text("".join(json.dumps(r) + "\n" for r in lines), encoding="utf-8")

    def test_features_are_score_and_margins(self):
        F = lc.features([np.array([[1.0, 0.5], [0.0, 0.25]])])
        self.assertEqual(F.shape, (4, 3))
        self.assertEqual(F[1].tolist(), [0.5, -0.5, 0.0])

    @unittest.skipUnless(HAS_SKLEARN, "scikit-learn is not installed")
    def test_leave_one_family_out_writes_replayable_matrices(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            self.records(tmp / "input.jsonl")
            out = tmp / "learned"
            argv = ["--records", str(tmp / "input.jsonl"), "--bases", "good,noise", "--family-from-dataset", "--out", str(out)]
            self.assertEqual(lc.main(argv), 0)
            with (out / "B" / "s1" / "seed.csv").open(encoding="utf-8") as fh:
                rows = list(csv.reader(fh))
            self.assertEqual(rows[0], ["Source\\Target", "u.a", "u.b", "u.c"])
            P = np.array([[float(v) for v in r[1:]] for r in rows[1:]])
            self.assertTrue((P.diagonal() > 0.5).all())
            provenance = json.loads((out / "provenance.json").read_text())
            self.assertEqual(provenance["protocol"], "leave-one-family-out")
            self.assertEqual(set(provenance["coefficients_per_held_out_group"]), {"A", "B", "C"})
            self.assertEqual(lc.main(argv), 2)                                         # no overwrite
            self.assertEqual(lc.main(argv[:3] + ["oracle,good"] + argv[4:-1] + [str(tmp / "o2")]), 2)


if __name__ == "__main__":
    unittest.main()
