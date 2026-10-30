"""Positional audits of score matrices: does column order, rather than evidence, decide matches?

Three questions, each needing a different experiment:

alignment   How much of the truth lies on the diagonal, and what does an
            informationless constant matrix score under Hungarian extraction?
            `linear_sum_assignment` resolves a constant matrix to the identity,
            so a corpus whose splits preserve schema order rewards it.
extractor   The extraction-side subsidy. Permute the target columns of each
            score matrix and of its truth by the same pi and re-extract. The
            matcher is untouched, so any change is the tie-breaking of the
            extractor. Permutation-equivariant extractors (mutual top-1) are
            unchanged by construction and serve as a check. This mode cannot
            detect a matcher that reads column order.
rerun       The matcher-side test. Run the matcher on a column-permuted copy of
            the dataset (`transform_dataset.py permute`) and require
            S_permuted == S[rows, cols] elementwise. An order-blind matcher
            reproduces its own matrix exactly.

Input is the evaluator's `paper_evaluation/input.jsonl` (raw scores, aligned
truth). Needs NumPy and SciPy. See ../docs/evaluation.md.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
import sys

import numpy as np

from paper_metrics import f1, hungarian, mutual_top1

HERE = Path(__file__).resolve().parent
DEFAULT_REPEATS = 20
DEFAULT_SEED = 20260909


def tie_fraction(S: np.ndarray, eps: float = 1e-9) -> float:
    """Share of source rows whose maximum is not unique: what Hungarian must resolve."""
    if S.size == 0:
        return 0.0
    rmax = S.max(axis=1, keepdims=True)
    return float(((S >= rmax - eps).sum(axis=1) > 1).mean())


def spread(S: np.ndarray) -> float:
    """Relative dynamic range: how far from constant the matrix is."""
    if S.size == 0:
        return 0.0
    scale = max(abs(float(np.nanmax(np.abs(S)))), 1e-12)
    return float((np.nanmax(S) - np.nanmin(S)) / scale)


def alignment(gt: np.ndarray) -> dict:
    pairs = list(zip(*np.nonzero(gt)))
    off = [(i, j) for i, j in pairs if i != j]
    return {"n_source": gt.shape[0], "n_target": gt.shape[1], "n_ground_truth": len(pairs),
            "n_off_diagonal": len(off), "fully_diagonal": not off, "single_pair": len(pairs) == 1,
            "constant_f1_hungarian": f1(hungarian(np.ones(gt.shape)), gt),
            "constant_f1_mutual": f1(mutual_top1(np.ones(gt.shape)), gt)}


def draw_permutations(scenarios: list[tuple[str, int]], repeats: int = DEFAULT_REPEATS,
                      seed: int = DEFAULT_SEED) -> dict[str, list[np.ndarray]]:
    """One set of target-column permutations per scenario, drawn in the given order and shared by all
    matchers, so that matchers are compared under identical relabellings."""
    rng = np.random.default_rng(seed)
    return {key: [rng.permutation(n) for _ in range(repeats)] for key, n in scenarios}


def extractor_subsidy(S: np.ndarray, gt: np.ndarray, perms: list[np.ndarray], native: bool = False) -> dict:
    """Hungarian F1 before/after relabelling target columns; a native decision is not re-extracted."""
    if native:
        base_h = f1(S > 0.5, gt)
        perm_h = [f1(S[:, pi] > 0.5, gt[:, pi]) for pi in perms]
        out = {}
    else:
        base_h = f1(hungarian(S), gt)
        perm_h = [f1(hungarian(S[:, pi]), gt[:, pi]) for pi in perms]
        base_m = f1(mutual_top1(S), gt)
        perm_m = float(np.mean([f1(mutual_top1(S[:, pi]), gt[:, pi]) for pi in perms]))
        out = {"mut": base_m, "mut_perm": perm_m, "subsidy_mutual": base_m - perm_m}
    out.update(hung=base_h, hung_perm=float(np.mean(perm_h)), tie=tie_fraction(S), spread=spread(S))
    out["subsidy"] = out["hung"] - out["hung_perm"]
    return out


def rerun_check(S: np.ndarray, S_perm: np.ndarray, rows: list[int], cols: list[int], gt: np.ndarray) -> dict:
    """Compare a matcher's output on permuted tables with its original output, permuted alike."""
    expected = S[np.ix_(rows, cols)]
    if S_perm.shape != expected.shape:
        raise ValueError(f"shape {S_perm.shape} differs from {expected.shape}")
    delta = float(np.abs(S_perm - expected).max()) if S.size else 0.0
    gt_perm = gt[np.ix_(rows, cols)]
    return {"max_abs_delta": delta, "f1_hung": f1(hungarian(S), gt), "f1_hung_permuted": f1(hungarian(S_perm), gt_perm)}


def read_records(path: Path):
    with path.open(encoding="utf-8-sig") as stream:
        for line in stream:
            if line.strip():
                r = json.loads(line)
                yield r, np.asarray(r["scores"], dtype=float), np.asarray(r["ground_truth"]).astype(bool)


def identity(r: dict) -> tuple:
    return r["dataset"], r["scenario"], r["stage"], r["matcher"]


def summarise(rows: list[dict], by: tuple, fields: tuple) -> list[dict]:
    acc = defaultdict(lambda: defaultdict(list))
    for row in rows:
        for f in fields:
            if f in row:
                acc[tuple(row[k] for k in by)][f].append(row[f])
    out = []
    for key, values in acc.items():
        item = dict(zip(by, key))
        item["n"] = max(len(v) for v in values.values())
        item.update({f: float(np.mean(v)) for f, v in values.items()})
        out.append(item)
    return out


def write_csv(path: Path, rows: list[dict]) -> None:
    fields = []
    for row in rows:
        fields += [k for k in row if k not in fields]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def command_alignment(args) -> list[dict]:
    rows, seen = [], set()
    for r, _, gt in read_records(args.records):
        key = f"{r['dataset']}/{r['scenario']}"
        if key not in seen:
            seen.add(key)
            rows.append({"dataset": r["dataset"], "scenario": r["scenario"], **alignment(gt)})
    write_csv(args.out / "alignment.csv", rows)
    pairs = sum(r["n_ground_truth"] for r in rows)
    off = sum(r["n_off_diagonal"] for r in rows)
    print(f"{len(rows)} scenarios, {pairs} truth pairs, {off} off the diagonal; "
          f"{sum(r['fully_diagonal'] for r in rows)} fully diagonal; constant matrix mean Hungarian F1 "
          f"{np.mean([r['constant_f1_hungarian'] for r in rows]):.4f}")
    return rows


def command_extractor(args) -> list[dict]:
    records = list(read_records(args.records))
    order, shapes = [], {}
    for r, S, gt in records:
        key = f"{r['dataset']}/{r['scenario']}"
        if key not in shapes:
            shapes[key] = gt.shape[1]
            order.append((key, gt.shape[1]))
    perms = draw_permutations(order, args.repeats, args.seed)
    rows = []
    for r, S, gt in records:
        if S.shape != gt.shape or not np.isfinite(S).all() or not gt.any():
            continue
        result = extractor_subsidy(S, gt, perms[f"{r['dataset']}/{r['scenario']}"])
        rows.append({"dataset": r["dataset"], "scenario": r["scenario"], "stage": r["stage"],
                     "matcher": r["matcher"], **result})
    done = set()
    for r, S, gt in records:                 # the informationless reference, once per scenario
        key = f"{r['dataset']}/{r['scenario']}"
        if key not in done and gt.any():
            done.add(key)
            rows.append({"dataset": r["dataset"], "scenario": r["scenario"], "stage": "reference",
                         "matcher": "constant", **extractor_subsidy(np.ones(gt.shape), gt, perms[key])})
    write_csv(args.out / "extractor_scenarios.csv", rows)
    summary = summarise(rows, ("stage", "matcher"), ("hung", "hung_perm", "subsidy", "mut", "mut_perm",
                                                     "subsidy_mutual", "tie", "spread"))
    write_csv(args.out / "extractor_summary.csv", summary)
    worst = max((abs(r.get("subsidy_mutual", 0.0)) for r in rows), default=0.0)
    for s in sorted(summary, key=lambda s: -s["subsidy"]):
        print(f"{s['stage']:<20} {s['matcher']:<40} n={s['n']:<4} F1_H {s['hung']:.4f} permuted {s['hung_perm']:.4f} "
              f"subsidy {s['subsidy']:+.4f} ties {100 * s['tie']:.1f}%")
    print(f"equivariance check (must be 0): mutual top-1 max |subsidy| {worst:.2e}")
    return rows


def command_rerun(args) -> list[dict]:
    sys.path.insert(0, str(HERE))
    from dataset_support.transforms import global_order
    orders = json.loads(args.permutations.read_text(encoding="utf-8"))["scenarios"]
    original = {identity(r): (S, gt) for r, S, gt in read_records(args.original)}
    rows, missing = [], 0
    for r, S_perm, _ in read_records(args.permuted):
        key = f"{r['dataset']}/{r['scenario']}"
        if identity(r) not in original or key not in orders:
            missing += 1
            continue
        S, gt = original[identity(r)]
        rows_order = global_order(orders[key]["source"])
        cols_order = global_order(orders[key]["target"])
        rows.append({"dataset": r["dataset"], "scenario": r["scenario"], "stage": r["stage"], "matcher": r["matcher"],
                     **rerun_check(S, S_perm, rows_order, cols_order, gt)})
    write_csv(args.out / "rerun.csv", rows)
    by = defaultdict(list)
    for row in rows:
        by[(row["stage"], row["matcher"])].append(row)
    for (stage, matcher), items in sorted(by.items()):
        print(f"{stage:<20} {matcher:<40} n={len(items):<4} max|dS| {max(i['max_abs_delta'] for i in items):.3e} "
              f"> 1e-12: {sum(i['max_abs_delta'] > 1e-12 for i in items)}  > 1e-9: {sum(i['max_abs_delta'] > 1e-9 for i in items)}"
              f"  F1_H moved: {sum(abs(i['f1_hung'] - i['f1_hung_permuted']) > 1e-12 for i in items)}")
    if missing:
        print(f"{missing} permuted records without an original record or permutation")
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("alignment", "extractor"):
        child = commands.add_parser(name)
        child.add_argument("--records", type=Path, required=True, help="paper_evaluation/input.jsonl")
        child.add_argument("--out", type=Path, required=True)
        if name == "extractor":
            child.add_argument("--repeats", type=int, default=DEFAULT_REPEATS)
            child.add_argument("--seed", type=int, default=DEFAULT_SEED)
    child = commands.add_parser("rerun")
    child.add_argument("--original", type=Path, required=True, help="input.jsonl of the run on the original dataset")
    child.add_argument("--permuted", type=Path, required=True, help="input.jsonl of the run on the permuted copy")
    child.add_argument("--permutations", type=Path, required=True,
                       help="transformation_manifest.json written by transform_dataset.py permute")
    child.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    try:
        {"alignment": command_alignment, "extractor": command_extractor, "rerun": command_rerun}[args.command](args)
        return 0
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as exc:
        print(f"Positional audit failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
