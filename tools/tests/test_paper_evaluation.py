"""Standalone evaluator checks against frozen synthetic reference cases."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOLS))
import paper_metrics as pm
import evaluate_paper as ep


class PaperMetricsTest(unittest.TestCase):
    def test_flat_matrix_exposes_index_bias_and_exact_expected_ranks(self):
        scores = np.ones((2,2))
        truth = np.eye(2,dtype=bool)
        result = pm.all_metrics(scores,truth,pm.permutations(scores.shape,"toy/case"))
        self.assertEqual(result["F1_H"],1)
        self.assertAlmostEqual(result["F1_M"],2/3)
        self.assertEqual(result["R_exp"],.5)
        self.assertEqual(result["R_pess"],0)
        self.assertEqual(result["MRR_exp"],.75)
        self.assertEqual(result["MRR_pess"],.5)
        self.assertLess(result["F1_Hr"],1)
        self.assertEqual(len(pm.EXTRACTORS),9)

    def test_paired_reports_use_explicit_families_and_cluster_units(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            records = []
            for scenario in ("a","b"):
                for stage,matcher,scores in (("MatchingStepLine1","base",[[1,1],[1,1]]),
                                              ("MatchingStepLine2","refined",[[2,0],[0,2]])):
                    records.append({"dataset":"toy","scenario":scenario,"stage":stage,"matcher":matcher,"baseline":"base",
                                    "source_labels":["s.a","s.b"],"target_labels":["t.a","t.b"],
                                    "scores":scores,"ground_truth":[[1,0],[0,1]]})
            path = root/"input.jsonl"
            path.write_text("".join(json.dumps(r)+"\n" for r in records),encoding="utf-8")
            groups = root/"groups.json"
            groups.write_text(json.dumps({f"toy/{s}":{"family":"F","unit":s,"regime":"readable","split":"test"}
                                          for s in ("a","b")}),encoding="utf-8")
            args = argparse.Namespace(records=str(path),out=str(root/"out"),groups=str(groups),bootstrap=20,min_family_size=1,seed=123)
            manifest = ep.run(args)
            self.assertEqual(manifest["records"],4)
            self.assertEqual(manifest["paired_records"],2)
            with (root/"out/metrics.csv").open(newline="",encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
            self.assertTrue(all(float(r["J_tf"]) == 1 for r in rows if r["matcher"]=="refined"))
            with (root/"out/paired.csv").open(newline="",encoding="utf-8") as stream:
                paired = list(csv.DictReader(stream))
            self.assertTrue(all(float(r["d_R_exp"]) == .5 for r in paired))
            args.groups = None
            with self.assertRaisesRegex(ValueError,"explicit --groups"):
                ep.run(args)

    def test_zero_truth_is_reported_undefined_and_nonpositive_scores_are_not_matches(self):
        scores = np.zeros((2,3))
        truth = np.zeros((2,3),bool)
        result = pm.all_metrics(scores,truth,pm.permutations(scores.shape,"zero"))
        self.assertTrue(np.isnan(result["R_exp"]))
        self.assertTrue(np.isnan(result["MRR_exp"]))
        self.assertEqual(result["F1_Hr"],0)
        self.assertFalse(pm.extract(scores,"hungarian").any())


class ReferenceParityTest(unittest.TestCase):
    def test_every_paper_metric_and_permutation_matches_original_implementations(self):
        import hashlib
        fixture = json.loads((TOOLS/"tests/fixtures/paper_metrics.json").read_text())
        self.assertEqual(len(fixture["cases"]),30)
        for case in fixture["cases"]:
            scores=np.asarray(case["scores"],float)
            truth=np.asarray(case["truth"],bool)
            permutations=pm.permutations(scores.shape,case["key"])
            digest=hashlib.sha256()
            for rows,cols in permutations:
                digest.update(np.asarray(rows,dtype="<i8").tobytes())
                digest.update(np.asarray(cols,dtype="<i8").tobytes())
            self.assertEqual(digest.hexdigest(),case["permutations_sha256"])
            actual=pm.all_metrics(scores,truth,permutations)
            for metric,value in case["metrics"].items():
                self.assertAlmostEqual(actual[metric],value,places=15,msg=metric)



if __name__ == "__main__":
    unittest.main()
