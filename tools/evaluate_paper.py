"""Evaluate raw Schematch matrices with the paper's exact ranking/tie/extraction functions.

Requires numpy and scipy, with no research-tree imports. Java's paperEvaluation mode
writes the input records and invokes this tool automatically. It can also be run later:
    python tools/evaluate_paper.py --records results/.../paper_evaluation/input.jsonl --out report
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
import scipy
from paper_metrics import ScoreMatrix, all_metrics, evaluate, permutations


def write_csv(path, rows):
    if not rows:
        path.write_text("", encoding="utf-8")
        return
    keys = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=keys)
        writer.writeheader()
        writer.writerows(rows)


def aggregate(rows, metrics, min_family_size):
    families = defaultdict(list)
    for row in rows:
        families[row["family"]].append(row)
    kept = {f: rs for f, rs in families.items() if len(rs) >= min_family_size}
    out = {"families": len(kept), "scenarios": sum(len(rs) for rs in kept.values()),
           "excluded_families": ";".join(sorted(set(families)-set(kept)))}
    for key in metrics:
        means = [np.mean([r[key] for r in rs if np.isfinite(r[key])])
                 for rs in kept.values() if any(np.isfinite(r[key]) for r in rs)]
        out[key] = float(np.mean(means)) if means else float("nan")
    return out


def clustered_bootstrap(rows, key, draws, rng, min_family_size):
    by = defaultdict(lambda: defaultdict(list))
    counts = defaultdict(int)
    for row in rows:
        counts[row["family"]] += 1
        if np.isfinite(row[key]):
            by[row["family"]][row["unit"]].append(row[key])
    by = {f: u for f, u in by.items() if counts[f] >= min_family_size}
    if not by:
        return [float("nan"), float("nan")]
    samples = []
    for _ in range(draws):
        family_means = []
        for family in sorted(by):
            units = by[family]
            blocks = [units[unit] for unit in sorted(units)]
            selected = rng.integers(0, len(blocks), len(blocks))
            family_means.append(np.mean([v for i in selected for v in blocks[i]]))
        samples.append(np.mean(family_means))
    return np.quantile(samples, [.025, .975]).tolist()


def run(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    groups = json.loads(Path(args.groups).read_text(encoding="utf-8-sig")) if args.groups else None
    if args.bootstrap and groups is None:
        raise ValueError("Clustered bootstrap requires an explicit --groups manifest with family and unit IDs")
    primary, selections, excluded = [], [], []
    metrics = ["F1_H", "F1_Hr", "F1_M", "R_pess", "R_exp", "MRR_pess", "MRR_exp", "J_tf", "J_preregistered"]
    seen = set()
    with Path(args.records).open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            record = json.loads(line)
            identity = (record["dataset"], record["scenario"], record["stage"], record["matcher"])
            if identity in seen:
                raise ValueError(f"Duplicate evaluation record {identity} at line {line_number}")
            seen.add(identity)
            key = f"{record['dataset']}/{record['scenario']}"
            group = groups.get(key) if groups is not None else {"family": record["dataset"], "unit": key}
            if group is None:
                raise ValueError(f"Grouping/selection manifest has no entry for {key}")
            if group.get("include", True) is False:
                excluded.append({"dataset": record["dataset"], "scenario": record["scenario"], "reason": group.get("reason", "explicit exclusion")})
                continue
            if not group.get("family") or not group.get("unit"):
                raise ValueError(f"Missing family or resampling unit for {key}")
            scores = np.asarray(record["scores"], dtype=float)
            truth = np.asarray(record["ground_truth"])
            if scores.ndim != 2 or scores.shape != truth.shape or not np.isfinite(scores).all():
                raise ValueError(f"Invalid score/truth dimensions or scores at {key}")
            if not np.isin(truth, [0, 1]).all():
                raise ValueError(f"Non-binary truth at {key}")
            truth = truth.astype(bool)
            if len(record["source_labels"]) != scores.shape[0] or len(record["target_labels"]) != scores.shape[1]:
                raise ValueError(f"Column labels do not match dimensions at {key}")
            permutation_key = group.get("permutation_key", key)
            result = all_metrics(scores, truth, permutations(scores.shape, permutation_key))
            result["J_tf"] = (result["R_exp"] + result["F1_Hr"]) / 2
            result["J_preregistered"] = (result["R_pess"] + result["F1_H"]) / 2
            meta = {k: record.get(k, "") for k in ("dataset", "scenario", "stage", "matcher", "baseline", "wall_ms", "from_cache")}
            meta.update({k: v for k, v in group.items() if k not in {"include", "reason", "permutation_key"}})
            meta["permutation_key"] = permutation_key
            primary.append({**meta, **result})
            selections.extend(evaluate(ScoreMatrix(scores, truth, meta=meta)))
    if not primary:
        raise ValueError("No evaluation records selected")
    baselines = {(r["dataset"], r["scenario"], r["matcher"]): r for r in primary if r["stage"] == "MatchingStepLine1"}
    paired, unpaired = [], []
    for row in primary:
        if row["stage"] != "MatchingStepLine2":
            continue
        baseline = baselines.get((row["dataset"], row["scenario"], row["baseline"]))
        if baseline is None:
            unpaired.append({k: row[k] for k in ("dataset", "scenario", "matcher", "baseline")})
            continue
        paired.append({**{k: v for k, v in row.items() if k not in metrics},
                       **{f"d_{metric}": row[metric]-baseline[metric] for metric in metrics}})
    selection_fields = ["precision", "recall", "f1", "overall", "n_match", "match_ratio", "tp", "fp", "fn"]
    selection_bases = {(r["dataset"],r["scenario"],r["matcher"],r["extractor"]):r
                       for r in selections if r["stage"] == "MatchingStepLine1"}
    paired_selections = []
    for row in selections:
        if row["stage"] != "MatchingStepLine2":
            continue
        baseline = selection_bases.get((row["dataset"],row["scenario"],row["baseline"],row["extractor"]))
        if baseline is not None:
            paired_selections.append({**{k:v for k,v in row.items() if k not in selection_fields},
                                      **{"d_"+k:row[k]-baseline[k] for k in selection_fields}})
    summary, paired_summary, selection_summary, paired_selection_summary = [], [], [], []
    strata = ("regime", "split", "corpus", "group")
    for rows, fields, target in ((primary, metrics, summary), (paired, ["d_"+m for m in metrics], paired_summary),
                                 (selections,selection_fields,selection_summary),
                                 (paired_selections,["d_"+m for m in selection_fields],paired_selection_summary)):
        by = defaultdict(list)
        for row in rows:
            ident = tuple((k, row.get(k, "")) for k in ("stage", "matcher", "baseline", "extractor", *strata))
            by[ident].append(row)
        for ident, items in by.items():
            report = {**dict(ident), **aggregate(items, fields, args.min_family_size)}
            if rows is paired or rows is paired_selections:
                effect_eps = getattr(args,"effect_eps",1e-12)
                family_counts = defaultdict(int)
                for row in items:
                    family_counts[row["family"]] += 1
                eligible = [row for row in items if family_counts[row["family"]] >= args.min_family_size]
                for key in fields:
                    values = [r[key] for r in eligible if np.isfinite(r[key])]
                    report[key+"_wins"] = sum(v > effect_eps for v in values)
                    report[key+"_losses"] = sum(v < -effect_eps for v in values)
                    report[key+"_ties"] = sum(abs(v) <= effect_eps for v in values)
            if args.bootstrap and (rows is paired or rows is paired_selections):
                rng = np.random.default_rng(args.seed)
                for key in fields:
                    lo, hi = clustered_bootstrap(items, key, args.bootstrap, rng, args.min_family_size)
                    report[key+"_lo"], report[key+"_hi"] = lo, hi
            target.append(report)
    for filename, rows in (("metrics.csv",primary), ("extractions.csv",selections), ("paired.csv",paired),
                           ("summary.csv",summary), ("paired_summary.csv",paired_summary),
                           ("selection_summary.csv",selection_summary), ("paired_extractions.csv",paired_selections),
                           ("paired_selection_summary.csv",paired_selection_summary), ("excluded.csv",excluded), ("unpaired.csv",unpaired)):
        write_csv(out/filename, rows)
    manifest = {"records": len(primary), "paired_records": len(paired), "unpaired_records": len(unpaired),
                "groups": args.groups, "min_family_size": args.min_family_size, "bootstrap": args.bootstrap,
                "bootstrap_seed": args.seed, "hungarian_permutations": 50, "eps": 1e-9,
                "win_loss_effect_eps":getattr(args,"effect_eps",1e-12),
                "numpy": np.__version__, "scipy": scipy.__version__,
                "grouping_default": "dataset, descriptive only" if groups is None else "explicit manifest"}
    (out/"evaluation_manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    print(f"Paper evaluation: {len(primary)} matrices, {len(paired)} paired differences -> {out}")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--groups", help="JSON mapping dataset/scenario to family, unit, optional strata/include/permutation_key")
    parser.add_argument("--min-family-size", type=int, default=1)
    parser.add_argument("--bootstrap", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20261014)
    parser.add_argument("--effect-eps", type=float, default=1e-12, help="Tolerance for descriptive paired win/loss counts")
    args = parser.parse_args()
    if args.min_family_size < 1 or args.bootstrap < 0 or not np.isfinite(args.effect_eps) or args.effect_eps < 0:
        parser.error("min-family-size must be positive; bootstrap and effect-eps must be nonnegative")
    run(args)


if __name__ == "__main__":
    main()
