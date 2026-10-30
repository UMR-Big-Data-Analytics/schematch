"""Independent-sample multi-table recipe on a tiny synthetic Chinook-shaped database.

Byte parity of all 81 scenarios with the study's corpus is recorded in docs/datasets.md.
"""
import csv
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_support import indep


def chinook(folder: Path, invoices=320):
    tables = {
        "Artist": (["ArtistId", "Name"], [[1, "A"], [2, "B"]]),
        "Album": (["AlbumId", "Title", "ArtistId"], [[1, "X", 1], [2, "Y", 2]]),
        "Genre": (["GenreId", "Name"], [[1, "Rock"], [2, "Jazz"]]),
        "MediaType": (["MediaTypeId", "Name"], [[1, "MP3"]]),
        "Track": (["TrackId", "Name", "AlbumId", "MediaTypeId", "GenreId"],
                  [[t, f"t{t}", 1 + t % 2, 1, 1 + t % 2] for t in range(1, 5)]),
        "Customer": (["CustomerId", "FirstName", "Country"], [[c, f"c{c}", "DE" if c % 2 else ""] for c in range(1, 4)]),
        "Invoice": (["InvoiceId", "CustomerId", "Total"], [[i, 1 + i % 3, f"{i}.99"] for i in range(1, invoices + 1)]),
        "InvoiceLine": (["InvoiceLineId", "InvoiceId", "TrackId", "Quantity"],
                        [[i, i, 1 + i % 4, 1] for i in range(1, invoices + 1)]),
    }
    con = sqlite3.connect(folder / "Chinook_Sqlite.sqlite")
    for name, (cols, rows) in tables.items():
        con.execute(f'CREATE TABLE "{name}" ({", ".join(cols)})')
        con.executemany(f'INSERT INTO "{name}" VALUES ({", ".join("?" * len(cols))})', rows)
    con.commit()
    con.close()


def read(path):
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.reader(fh))


class IndepRecipeTest(unittest.TestCase):
    def test_variants_samples_and_provenance_truth(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            chinook(tmp)
            out = tmp / "out"
            report = indep.prepare(tmp, out, seeds=(7,), databases=["Chinook"])
            self.assertEqual(len(report["scenarios"]), 9)
            shared = {k.split("_")[1]: v["shared_base_rows"] for k, v in report["scenarios"].items()}
            self.assertEqual(shared, {"identical": 150, "half": 75, "disjoint": 0})
            self.assertFalse((out / ".chinook-export").exists())
            scen = out / "Indep-Chinook" / "s7_identical_N-D1"
            self.assertEqual(sorted(p.stem for p in (scen / "target").glob("*.csv")), ["genre", "invoice", "line", "media"])
            line = read(scen / "target" / "line.csv")[0]
            self.assertIn("artist_id", line)                     # snake_case target labels, joined parents
            prov = json.loads((scen / "provenance.json").read_text(encoding="utf-8"))
            gt = [[int(v) for v in r] for r in read(scen / "ground_truth_full.csv")]
            pairs = {(tuple(prov["source_columns"][i]), tuple(prov["target_columns"][j]))
                     for i, r in enumerate(gt) for j, v in enumerate(r) if v}
            self.assertIn((("Artist", "ArtistId"), ("line", "artist_id")), pairs)
            self.assertIn((("Album", "ArtistId"), ("line", "artist_id")), pairs)   # keys are n:m
            self.assertEqual(prov["gt_pairs"], len(pairs))
            block = read(scen / "ground_truth" / "Artist___line.csv")
            self.assertEqual(len(block), 2)
            self.assertEqual(len(block[0]), len(line))

    def test_seeded_reruns_are_identical_and_inputs_are_hashed(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            chinook(tmp)
            a = indep.prepare(tmp, tmp / "a", seeds=(3,), databases=["Chinook"])
            b = indep.prepare(tmp, tmp / "b", seeds=(3,), databases=["Chinook"])
            self.assertEqual(a, b)
            self.assertEqual(len(a["inputs"]), 8)
            for f in (tmp / "a").rglob("*.csv"):
                self.assertEqual(f.read_bytes(), (tmp / "b" / f.relative_to(tmp / "a")).read_bytes())

    def test_rejects_too_small_population(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            chinook(tmp, invoices=200)
            with self.assertRaisesRegex(ValueError, "at least 300"):
                indep.prepare(tmp, tmp / "out", seeds=(1,), databases=["Chinook"])


if __name__ == "__main__":
    unittest.main()
