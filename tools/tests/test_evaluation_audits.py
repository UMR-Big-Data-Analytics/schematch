"""Positional audits and table-sharing bootstrap units on synthetic inputs.

Parity with the original research scripts (382 Valentine scenarios, 200 GOBY
pairs) is recorded in EVALUATION_AND_DATASETS.md.
"""
from __future__ import annotations

import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import cluster_units as cu
import positional_audit as pa
from dataset_support import transforms


def record(dataset, scenario, matcher, scores, truth, stage="MatchingStepLine1"):
    n, m = np.asarray(truth).shape
    return {"dataset": dataset, "scenario": scenario, "stage": stage, "matcher": matcher,
            "scores": np.asarray(scores, dtype=float).tolist(), "ground_truth": np.asarray(truth, dtype=int).tolist(),
            "source_labels": [f"s{i}" for i in range(n)], "target_labels": [f"t{j}" for j in range(m)]}


def write_jsonl(path, records):
    path.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")


class PositionalAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def test_constant_matrix_collects_the_diagonal_and_loses_it_under_permutation(self):
        gt = np.eye(4, dtype=bool)
        self.assertEqual(pa.alignment(gt)["constant_f1_hungarian"], 1.0)
        self.assertTrue(pa.alignment(gt)["fully_diagonal"])
        perms = pa.draw_permutations([("d/s", 4)], repeats=20, seed=1)["d/s"]
        constant = pa.extractor_subsidy(np.ones((4, 4)), gt, perms)
        self.assertGreater(constant["subsidy"], 0.5)
        self.assertAlmostEqual(constant["subsidy_mutual"], 0.0, places=12)   # equivariant extractor
        informative = pa.extractor_subsidy(np.eye(4) * 0.9 + 0.05, gt, perms)
        self.assertEqual(informative["subsidy"], 0.0)
        self.assertEqual(informative["tie"], 0.0)

    def test_permutations_are_shared_per_scenario_in_first_seen_order(self):
        a = pa.draw_permutations([("x", 3), ("y", 5)], repeats=2, seed=7)
        b = pa.draw_permutations([("x", 3), ("y", 5)], repeats=2, seed=7)
        self.assertTrue(all(np.array_equal(p, q) for k in a for p, q in zip(a[k], b[k])))
        self.assertEqual(sorted(a["y"][0].tolist()), list(range(5)))

    def test_extractor_and_alignment_commands_write_reports(self):
        gt = np.eye(3, dtype=int)
        records = [record("D", "c1", "flat", np.ones((3, 3)), gt), record("D", "c1", "sharp", np.eye(3), gt),
                   record("D", "c2", "flat", np.ones((3, 3)), gt)]
        write_jsonl(self.root / "input.jsonl", records)
        out = self.root / "out"
        self.assertEqual(pa.main(["extractor", "--records", str(self.root / "input.jsonl"), "--out", str(out),
                                  "--repeats", "5"]), 0)
        with (out / "extractor_summary.csv").open(encoding="utf-8") as stream:
            summary = {r["matcher"]: r for r in csv.DictReader(stream)}
        self.assertEqual(set(summary), {"flat", "sharp", "constant"})
        self.assertEqual(float(summary["sharp"]["subsidy"]), 0.0)
        self.assertEqual(summary["flat"]["subsidy"], summary["constant"]["subsidy"])
        self.assertEqual(pa.main(["alignment", "--records", str(self.root / "input.jsonl"), "--out", str(out)]), 0)
        with (out / "alignment.csv").open(encoding="utf-8") as stream:
            self.assertEqual(len(list(csv.DictReader(stream))), 2)

    def test_rerun_detects_order_dependent_matchers(self):
        scenario = self.root / "in" / "D" / "c1"
        for side in ("source", "target"):
            path = scenario / side / f"{side}.csv"
            path.parent.mkdir(parents=True)
            path.write_text("a,b,c\n1,2,3\n", encoding="utf-8")
        orders = transforms.scenario_orders(scenario, "D")
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"scenarios": {"D/c1": orders}}), encoding="utf-8")
        rows, cols = orders["source"]["source"], orders["target"]["target"]
        S = np.arange(9, dtype=float).reshape(3, 3)
        gt = np.eye(3, dtype=int)
        write_jsonl(self.root / "a.jsonl", [record("D", "c1", "blind", S, gt), record("D", "c1", "positional", S, gt)])
        write_jsonl(self.root / "b.jsonl", [record("D", "c1", "blind", S[np.ix_(rows, cols)], gt[np.ix_(rows, cols)]),
                                            record("D", "c1", "positional", S, gt)])
        out = self.root / "rerun"
        self.assertEqual(pa.main(["rerun", "--original", str(self.root / "a.jsonl"), "--permuted", str(self.root / "b.jsonl"),
                                  "--permutations", str(manifest), "--out", str(out)]), 0)
        with (out / "rerun.csv").open(encoding="utf-8") as stream:
            delta = {r["matcher"]: float(r["max_abs_delta"]) for r in csv.DictReader(stream)}
        self.assertEqual(delta["blind"], 0.0)
        self.assertGreater(delta["positional"], 0.0)


class ClusterUnitsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def scenario(self, dataset, name, source, target, manifest=None):
        folder = self.root / "corpus" / dataset / name
        for side, content in (("source", source), ("target", target)):
            (folder / side).mkdir(parents=True)
            (folder / side / f"{side}.csv").write_text(content, encoding="utf-8")
        if manifest:
            (folder / "manifest.json").write_text(json.dumps({"tables": {
                side: {"raw_sha256": h} for side, h in zip(("source", "target"), manifest)}}), encoding="utf-8")

    def test_role_agnostic_components_and_side_restriction(self):
        self.scenario("G", "ab", "a\n1\n", "b\n2\n")
        self.scenario("G", "bc", "b\n2\n", "c\n3\n")     # shares table b, in the other role
        self.scenario("G", "de", "d\n4\n", "e\n5\n")
        groups, _ = cu.units(self.root / "corpus")
        self.assertEqual(groups["G/ab"]["unit"], groups["G/bc"]["unit"])
        self.assertNotEqual(groups["G/ab"]["unit"], groups["G/de"]["unit"])
        self.assertEqual(groups["G/de"], {"family": "G", "unit": "unit:G/de"})
        source_only, _ = cu.units(self.root / "corpus", sides=("source",))
        self.assertEqual(len({g["unit"] for g in source_only.values()}), 3)

    def test_manifest_hashes_take_precedence_and_cli_refuses_overwrite(self):
        self.scenario("G", "x", "a\n1\n", "b\n2\n", manifest=("h1", "h2"))
        self.scenario("G", "y", "c\n1\n", "d\n2\n", manifest=("h3", "h1"))
        groups, _ = cu.units(self.root / "corpus")
        self.assertEqual(groups["G/x"]["unit"], groups["G/y"]["unit"])
        files, _ = cu.units(self.root / "corpus", source="files")
        self.assertNotEqual(files["G/x"]["unit"], files["G/y"]["unit"])
        out = self.root / "groups.json"
        self.assertEqual(cu.main(["--input", str(self.root / "corpus"), "--out", str(out), "--family", "GOBY"]), 0)
        self.assertEqual(json.loads(out.read_text())["G/x"]["family"], "GOBY")
        self.assertEqual(cu.main(["--input", str(self.root / "corpus"), "--out", str(out)]), 2)


if __name__ == "__main__":
    unittest.main()
