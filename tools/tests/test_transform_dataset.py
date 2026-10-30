"""Contracts of the dataset transformations on small synthetic scenarios.

Byte parity with the original research scripts was checked separately on
Valentine and multi-table scenarios; see docs/datasets.md.
"""
import csv
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import transform_dataset as tool
from dataset_support import transforms as T


def write_csv(path, rows, terminator="\r\n"):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        csv.writer(fh, lineterminator=terminator).writerows(rows)


def read_csv(path):
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.reader(fh))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class TransformTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def single(self, name="case"):
        sc = self.root / "in" / "Data" / name
        write_csv(sc / "source" / "source.csv", [["id", "name", "city"], ["1", "a", ""], ["2", "b", "NA"], ["3", "a", "x"]])
        write_csv(sc / "target" / "target.csv", [["key", "label"], ["1", "a"], ["2", "null"]])
        write_csv(sc / "ground_truth" / "source___target.csv", [["1", "0"], ["0", "1"], ["0", "0"]], "\n")
        fd = sc / "metadata" / "source" / "source" / "FD_results.txt"
        fd.parent.mkdir(parents=True)
        fd.write_text("[source.csv.id] --> source.csv.name\n[source.csv.id,source.csv.name] --> source.csv.city\n",
                      encoding="utf-8")
        spec = {"status": "complete", "spec": {"table_sha256": "x", "columns": [
            {"id": f"c{i}", "index": i, "label": n, "original_label": n} for i, n in enumerate(["id", "name", "city"])]}}
        (fd.parent / "fd.scored.json").write_text(json.dumps(spec), encoding="utf-8")
        (sc / "metadata" / "profile-status.properties").write_text(
            f"source.table.sha256={sha(sc / 'source' / 'source.csv')}\nsource.fd=complete\n"
            f"source.fd.sha256={sha(fd)}\n", encoding="utf-8")
        return sc

    def multi(self):
        sc = self.root / "in" / "Multi" / "case"
        write_csv(sc / "source" / "a.csv", [["x", "y"], ["1", "2"]])
        write_csv(sc / "source" / "b.csv", [["z"], ["3"]])
        write_csv(sc / "target" / "c.csv", [["u", "v", "w"], ["1", "2", "3"]])
        write_csv(sc / "ground_truth" / "a___c.csv", [["1", "0", "0"], ["0", "0", "1"]], "\n")
        write_csv(sc / "ground_truth" / "b___c.csv", [["0", "1", "0"]], "\n")
        write_csv(sc / "ground_truth_full.csv", [["1", "0", "0"], ["0", "0", "1"], ["0", "1", "0"]], "\n")
        inds = sc / "metadata" / "source" / "inds.txt"
        inds.parent.mkdir(parents=True)
        inds.write_text("[a.csv.x] [= [b.csv.z]\n[b.csv.z] [= [a.csv.y]\n", encoding="utf-8")
        return sc

    def test_md5_keeps_nulls_salts_sides_and_relabels_profiles(self):
        sc = self.single()
        out = self.root / "out"
        rec, problems = T.md5_scenario(sc, out, "Data")
        self.assertEqual(problems, [])
        src, tgt = read_csv(out / "source" / "source.csv"), read_csv(out / "target" / "target.csv")
        self.assertEqual([src[1][2], src[2][2], tgt[2][1]], ["", "", ""])
        self.assertEqual(src[1][1], src[3][1])                                  # bijective within a side
        self.assertNotEqual(src[1][0], tgt[1][0])                               # per-side salt
        self.assertEqual((out / "ground_truth" / "source___target.csv").read_bytes(),
                         (sc / "ground_truth" / "source___target.csv").read_bytes())
        fd = (out / "metadata" / "source" / "source" / "FD_results.txt").read_text(encoding="utf-8")
        tokens = rec["sides"]["source"]["tables"][0]["columns"]
        self.assertIn(f"[source.csv.{tokens['id']['token']},source.csv.{tokens['name']['token']}]", fd)
        self.assertNotIn(".name", fd)
        spec = json.loads((out / "metadata" / "source" / "source" / "fd.scored.json").read_text(encoding="utf-8"))["spec"]
        self.assertEqual(spec["table_sha256"], sha(out / "source" / "source.csv"))
        status = (out / "metadata" / "profile-status.properties").read_text(encoding="utf-8")
        self.assertIn(f"source.fd.sha256={sha(out / 'metadata' / 'source' / 'source' / 'FD_results.txt')}", status)

    def test_md5_rejects_multitable_and_labeled_truth(self):
        with self.assertRaises(ValueError):
            T.md5_scenario(self.multi(), self.root / "o1", "Multi")
        sc = self.single("labeled")
        write_csv(sc / "ground_truth" / "source___target.csv", [["", "key", "label"], ["id", "1", "0"]])
        with self.assertRaises(ValueError):
            T.md5_scenario(sc, self.root / "o2", "Data")

    def test_opaque_labels_keep_values_and_shared_tokens(self):
        sc = self.single()
        write_csv(sc / "target" / "target.csv", [["id", "label"], ["1", "a"]])
        T.opaque_labels_dataset(sc.parent, self.root / "op", 1.0, 0)
        out = self.root / "op" / "case"
        src, tgt = read_csv(out / "source" / "source.csv"), read_csv(out / "target" / "target.csv")
        self.assertEqual(src[0][0], tgt[0][0])
        self.assertEqual(src[0][0], T.opaque_token("id"))
        self.assertEqual(src[1:], read_csv(sc / "source" / "source.csv")[1:])
        self.assertEqual((out / "metadata" / "source" / "source" / "FD_results.txt").read_text(), "")
        self.assertIn("source.fd=not_profiled", (out / "metadata" / "profile-status.properties").read_text())

    def test_opaque_tables_preserve_order_truth_and_relabel_references(self):
        sc = self.multi()
        for variant in ("on", "md5"):
            out = self.root / f"mt_{variant}"
            rec = T.opaque_tables_scenario(sc, out, "Multi", variant)
            tmap = rec["sides"]["source"]["tables"]
            self.assertEqual(sorted(tmap.values()), [tmap["a"], tmap["b"]])     # order-preserving relabel
            self.assertEqual((out / "ground_truth_full.csv").read_bytes(), (sc / "ground_truth_full.csv").read_bytes())
            self.assertNotIn("a.csv.x", (out / "metadata" / "source" / "inds.txt").read_text(encoding="utf-8"))
            values = read_csv(out / "source" / f"{tmap['a']}.csv")[1]
            self.assertEqual(values == ["1", "2"], variant == "on")

    def test_permute_round_trip_truth_and_specs(self):
        for sc, name in ((self.single(), "Data"), (self.multi(), "Multi")):
            out = self.root / f"perm_{name}"
            orders = T.permute_scenario(sc, out, name)
            for side in T.SIDES:
                for stem, order in orders[side].items():
                    self.assertNotEqual(order, sorted(order)) if len(order) > 1 else None
                    original, permuted = read_csv(sc / side / f"{stem}.csv"), read_csv(out / side / f"{stem}.csv")
                    self.assertEqual([[r[j] for j in order] for r in original], permuted)
            if name == "Multi":
                truth = read_csv(sc / "ground_truth_full.csv")
                permuted = read_csv(out / "ground_truth_full.csv")
                rows, cols = T.global_order(orders["source"]), T.global_order(orders["target"])
                self.assertEqual([[truth[i][j] for j in cols] for i in rows], permuted)
                # every positive still links the same pair of column labels
                labels_s = [h for stem in sorted(orders["source"]) for h in read_csv(out / "source" / f"{stem}.csv")[0]]
                labels_t = read_csv(out / "target" / "c.csv")[0]
                pairs = {(labels_s[i], labels_t[j]) for i, r in enumerate(permuted) for j, v in enumerate(r) if v == "1"}
                self.assertEqual(pairs, {("x", "u"), ("y", "w"), ("z", "v")})
            else:
                spec = json.loads((out / "metadata" / "source" / "source" / "fd.scored.json").read_text())["spec"]
                header = read_csv(out / "source" / "source.csv")[0]
                self.assertTrue(all(header[c["index"]] == c["label"] for c in spec["columns"]))
                self.assertIn(f"source.table.sha256={sha(out / 'source' / 'source.csv')}",
                              (out / "metadata" / "profile-status.properties").read_text())

    def test_permutation_is_seeded_and_never_identity(self):
        self.assertEqual(T.permutation(5, "k"), T.permutation(5, "k"))
        self.assertTrue(all(T.permutation(2, f"k{i}") == [1, 0] for i in range(20)))
        self.assertEqual(T.permutation(1, "k"), [0])

    def test_knob_k1_moves_truth_with_columns(self):
        sc = self.single()
        out = self.root / "k1"
        T.knob_scenario(sc, out, "K1", "x79|Data|case")
        header = read_csv(out / "target" / "target.csv")[0]
        truth = read_csv(out / "ground_truth" / "source___target.csv")
        self.assertEqual(truth[0][header.index("key")], "1")
        self.assertEqual(truth[1][header.index("label")], "1")
        with self.assertRaises(ValueError):
            T.knob_scenario(sc, self.root / "bad", "K9", "x")

    def test_cli_selection_manifest_and_refusal_to_overwrite(self):
        self.single("one")
        self.single("two")
        out = self.root / "cli"
        argv = ["md5", "--input", str(self.root / "in"), "--output", str(out), "--select", "Data/one"]
        self.assertEqual(tool.main(argv), 0)
        manifest = json.loads((out / "transformation_manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(list(manifest["scenarios"]), ["Data/one"])
        self.assertNotIn(str(self.root), json.dumps(manifest))
        self.assertEqual(tool.main(argv), 2)
        self.assertEqual(tool.main(["md5", "--input", str(self.root / "in"), "--output", str(self.root / "c2"),
                                    "--select", "None/*"]), 2)
        self.assertFalse((self.root / "c2").exists())
        self.assertEqual([p for p in self.root.iterdir() if p.name.startswith(".transform-")], [])


if __name__ == "__main__":
    unittest.main()
