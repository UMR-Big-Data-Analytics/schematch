"""Learned combination of first-line matchers, trained leave-one-family-out, written as replayable score matrices.

LSD's meta-learner (Doan, Domingos & Halevy, SIGMOD 2001), YAM (Duchateau et al., CIKM 2009) and corpus-based matching
(Madhavan et al., ICDE 2005) learn a matcher combination from labelled matches of *other* schemas, so they cannot be a
per-scenario Schematch matcher. This tool is that family over a finished run:

  features   per base matcher k and pair (i, j): s_k(i,j), s_k(i,j) - max_j' s_k(i,j'), s_k(i,j) - max_i' s_k(i',j)
             (raw score clipped to [-1, 1], and its margins to the row and column best)
  learner    class-balanced logistic regression (scikit-learn), C fixed, not tuned
  protocol   leave-one-family-out: a scenario's combiner never saw a scenario of its family (scenario types of one
             source family share tables). With a single family it falls back to leave-one-scenario-out and says so.
  output     <out>/<dataset>/<scenario>/seed.csv (header Source\\Target, table.column labels) for FileSeedMatcher
             (seedRoot = parent of <out>, seed = name of <out>) or Similarity Flooding's FILE input; provenance.json.

Input is the evaluator's paper_evaluation/input.jsonl, which carries the raw first-line matrices, aligned truth and
labels. Families come from an evaluator grouping manifest (--groups) or, explicitly, from dataset names. Truth of the
held-out family is never used for its own scores. Needs NumPy and scikit-learn. See ../docs/evaluation.md.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
import sys

import numpy as np

CONTROLS = ("oracle", "constant", "random", "embedding_random")


def features(matrices: list[np.ndarray]) -> np.ndarray:
    cols = []
    for S in matrices:
        cols.append(S)
        cols.append(S - S.max(axis=1, keepdims=True))
        cols.append(S - S.max(axis=0, keepdims=True))
    return np.stack([c.ravel() for c in cols], axis=1)


def combine(data: dict, groups: dict, C: float = 1.0):
    """data: {key: ([base matrices], truth)}, keys sorted; groups: {key: held-out group}.
    Returns ({key: probability matrix}, {group: coefficients})."""
    from sklearn.linear_model import LogisticRegression
    by_group = defaultdict(list)
    for k, g in groups.items():
        by_group[g].append(k)
    out, weights = {}, {}
    for g, held_out in sorted(by_group.items()):
        train = [k for k in data if groups[k] != g]
        if not train:
            raise ValueError(f"no training scenarios when holding out {g}")
        X = np.concatenate([features(data[k][0]) for k in train])
        y = np.concatenate([data[k][1].ravel() for k in train])
        if y.sum() == 0 or y.sum() == len(y):
            raise ValueError(f"training set without both classes when holding out {g}")
        model = LogisticRegression(C=C, class_weight="balanced", max_iter=2000)
        model.fit(X, y)
        weights[g] = model.coef_.ravel().round(4).tolist()
        for k in held_out:
            mats, gt = data[k]
            out[k] = model.predict_proba(features(mats))[:, 1].reshape(gt.shape)
    return out, weights


def write_seed(path: Path, S: np.ndarray, rows: list[str], cols: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["Source\\Target"] + cols)
        for label, row in zip(rows, S):
            w.writerow([label] + [repr(float(v)) for v in row])


def load(records: Path, bases: list[str], stage: str = "MatchingStepLine1"):
    """{(dataset, scenario): ([clipped base matrices], truth, (rows, cols))} for scenarios with every base finite."""
    found = defaultdict(dict)
    with records.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if not line.strip():
                continue
            r = json.loads(line)
            if r["stage"] == stage and r["matcher"] in bases:
                found[(r["dataset"], r["scenario"])][r["matcher"]] = r
    data, skipped = {}, []
    for key in sorted(found):
        if set(found[key]) != set(bases):
            skipped.append(key)
            continue
        first = found[key][bases[0]]
        gt = np.asarray(first["ground_truth"]) > 0.5
        mats = [np.asarray(found[key][b]["scores"], dtype=float) for b in bases]
        if not all(np.isfinite(m).all() and m.shape == gt.shape for m in mats):
            skipped.append(key)
            continue
        data[key] = ([np.clip(m, -1.0, 1.0) for m in mats], gt, (first["source_labels"], first["target_labels"]))
    return data, skipped


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--records", type=Path, required=True, help="paper_evaluation/input.jsonl of a finished run")
    parser.add_argument("--bases", required=True, help="Comma-separated first-line matcher names, as in the records")
    parser.add_argument("--out", type=Path, required=True, help="New directory for <dataset>/<scenario>/seed.csv")
    family = parser.add_mutually_exclusive_group(required=True)
    family.add_argument("--groups", type=Path, help="Evaluator grouping manifest with a 'family' per dataset/scenario")
    family.add_argument("--family-from-dataset", action="store_true", help="Use each dataset name as its family")
    parser.add_argument("--stage", default="MatchingStepLine1")
    parser.add_argument("--C", type=float, default=1.0, help="Inverse L2 strength; fixed, not tuned")
    args = parser.parse_args(argv)
    try:
        bases = [b for b in args.bases.split(",") if b]
        if any(b.lower().startswith(c) for b in bases for c in CONTROLS):
            raise ValueError("controls cannot be combiner features")
        if args.out.exists():
            raise FileExistsError(f"Refusing to overwrite {args.out}")
        data, skipped = load(args.records, bases, args.stage)
        if not data:
            raise ValueError("no scenario has every base matrix")
        if args.groups:
            manifest = json.loads(args.groups.read_text(encoding="utf-8-sig"))
            missing = [f"{d}/{s}" for d, s in data if not manifest.get(f"{d}/{s}", {}).get("family")]
            if missing:
                raise ValueError(f"grouping manifest has no family for {missing[:3]}")
            groups = {k: manifest[f"{k[0]}/{k[1]}"]["family"] for k in data}
        else:
            groups = {k: k[0] for k in data}
        protocol = "leave-one-family-out"
        if len(set(groups.values())) < 2:
            print("only one family present: falling back to leave-one-SCENARIO-out")
            groups = {k: f"{k[0]}/{k[1]}" for k in data}
            protocol = "leave-one-scenario-out"
        scores, weights = combine({k: v[:2] for k, v in data.items()}, groups, args.C)
        for k, P in scores.items():
            rows, cols = data[k][2]
            write_seed(args.out / k[0] / k[1] / "seed.csv", P, rows, cols)
        counts = defaultdict(int)
        for g in groups.values():
            counts[g] += 1
        (args.out / "provenance.json").write_text(json.dumps({
            "records": args.records.name, "stage": args.stage, "bases": bases,
            "features": "score, row margin, column margin", "clip": [-1.0, 1.0],
            "learner": f"LogisticRegression(C={args.C}, class_weight=balanced)", "protocol": protocol,
            "groups": dict(counts), "skipped_incomplete": [f"{d}/{s}" for d, s in skipped],
            "coefficients_per_held_out_group": weights}, indent=2), encoding="utf-8")
        print(f"wrote {len(scores)} learned matrices under {args.out} ({len(counts)} held-out groups, {len(bases)} bases"
              f"{f', {len(skipped)} incomplete scenarios skipped' if skipped else ''})")
        return 0
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as exc:
        print(f"Learned combination failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
