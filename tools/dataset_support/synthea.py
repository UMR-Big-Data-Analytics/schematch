"""Prepare Synthea to OMOP with OMAP truth and the study sampling policy.
Predefined header aliases and unavailable attributes are recorded in the report."""

from __future__ import annotations

import csv
import json
import random
import shutil
import sys
from collections import defaultdict
from pathlib import Path

from .xlsx_read import read_xlsx

SEEDS = (20261005, 20261006, 20261007)
CAP = 5000
PARENTS = {"source": {"patients", "encounters", "providers", "organizations"},
           "target": {"person", "visit_occurrence", "observation_period"}}
RENAME_SOURCE = {"encounters-cost": "BASE_ENCOUNTER_COST", "immunizations-cost": "BASE_COST",
                 "medications-cost": "BASE_COST", "procedures-cost": "BASE_COST", "procedures-date": "START",
                 "imaging_studies-body site code": "BODYSITE_CODE",
                 "imaging_studies-body site description": "BODYSITE_DESCRIPTION",
                 "imaging_studies-modality code": "MODALITY_CODE",
                 "imaging_studies-modality description": "MODALITY_DESCRIPTION",
                 "imaging_studies-sop code": "SOP_CODE", "imaging_studies-sop description": "SOP_DESCRIPTION"}
RENAME_TARGET = {"visit_occurrence-admitted_from_concept_id": "ADMITTING_SOURCE_CONCEPT_ID",
                 "visit_occurrence-admitted_from_source_value": "ADMITTING_SOURCE_VALUE"}


def header(path: Path) -> list[str]:
    with path.open(encoding="utf-8", newline="") as fh:
        return next(csv.reader(fh))


def resolve(attrs: list[str], folder: Path, rename: dict, upper_files: bool):
    """attribute 'table-col' -> (table, file column) or None."""
    files = {p.stem.lower(): p for p in folder.glob("*.csv")}
    out = {}
    for a in attrs:
        t, c = a.split("-", 1)
        f = files.get(t)
        if f is None:
            out[a] = None
            continue
        hdr = header(f)
        want = rename.get(a, c)
        hit = [h for h in hdr if h.lower() == want.lower()]
        out[a] = (t, hit[0]) if hit else None
    return out


def read(path: Path) -> tuple[list[str], list[list[str]]]:
    with path.open(encoding="utf-8", newline="") as fh:
        r = csv.reader(fh)
        hdr = next(r)
        return hdr, [row for row in r if row]


def prepare(raw: Path, output: Path) -> int:
    RAW, OUT = raw, output
    rows = read_xlsx(RAW / "omop_synthea_data.xlsx")["Sheet1"][1:]
    omop_attrs = sorted({r[0] for r in rows})
    syn_attrs = sorted({r[1] for r in rows})
    pos = sorted({(r[1], r[0]) for r in rows if r[4] == "1"})          # (synthea, omop)
    src_map = resolve(syn_attrs, RAW / "synthea", RENAME_SOURCE, True)
    tgt_dir = RAW / "omop" / "GiBleed_5.3"
    tgt_map = resolve(omop_attrs, tgt_dir, RENAME_TARGET, True)
    nonempty = set()
    for p in tgt_dir.glob("*.csv"):
        with p.open(encoding="utf-8") as stream:
            if sum(1 for _ in stream) > 1:
                nonempty.add(p.stem.lower())
    tgt_map = {a: (v if v and v[0] in nonempty else None) for a, v in tgt_map.items()}
    dropped = {"source": sorted(a for a, v in src_map.items() if v is None),
               "target": sorted(a for a, v in tgt_map.items() if v is None)}
    kept_pos = [(s, o) for s, o in pos if src_map[s] and tgt_map[o]]
    lost = [(s, o) for s, o in pos if not (src_map[s] and tgt_map[o])]
    cols = {"source": defaultdict(list), "target": defaultdict(list)}
    for side, mp in (("source", src_map), ("target", tgt_map)):
        for a, v in mp.items():
            if v and v[1] not in cols[side][v[0]]:
                cols[side][v[0]].append(v[1])
    files = {"source": {p.stem.lower(): p for p in (RAW / "synthea").glob("*.csv")},
             "target": {p.stem.lower(): p for p in tgt_dir.glob("*.csv")}}
    data = {}
    for side in ("source", "target"):
        for t, cs in cols[side].items():
            hdr, body = read(files[side][t])
            # keep the file's column order
            keep = [h for h in hdr if h in cs]
            idx = [hdr.index(h) for h in keep]
            data[(side, t)] = (keep, [[r[i] if i < len(r) else "" for i in idx] for r in body])
    truth = {(src_map[s], tgt_map[o]) for s, o in kept_pos}
    if OUT.exists():
        raise FileExistsError(f"Refusing to overwrite {OUT}")
    stats = []

    def write_scenario(folder: Path, src_tables, tgt_tables, sampled, seed, kind):
        for side, tabs in (("source", src_tables), ("target", tgt_tables)):
            (folder / side).mkdir(parents=True, exist_ok=True)
            for t in tabs:
                keep, body = sampled[(side, t)]
                with (folder / side / f"{t}.csv").open("w", encoding="utf-8", newline="") as fh:
                    w = csv.writer(fh)
                    w.writerow(keep)
                    w.writerows(body)
        sc = [(t, c) for t in sorted(src_tables) for c in sampled[("source", t)][0]]
        tc = [(t, c) for t in sorted(tgt_tables) for c in sampled[("target", t)][0]]
        (folder / "ground_truth").mkdir(exist_ok=True)
        with (folder / "ground_truth_full.csv").open("w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            for s in sc:
                w.writerow([1 if (s, t) in truth else 0 for t in tc])
        for st in sorted(src_tables):
            for tt in sorted(tgt_tables):
                with (folder / "ground_truth" / f"{st}___{tt}.csv").open("w", encoding="utf-8", newline="") as fh:
                    w = csv.writer(fh)
                    for c in sampled[("source", st)][0]:
                        w.writerow([1 if ((st, c), (tt, d)) in truth else 0 for d in sampled[("target", tt)][0]])
        n_pos = sum(1 for s in sc for t in tc if (s, t) in truth)
        prov = {"kind": kind, "seed": seed, "source_columns": [f"{t}.{c}" for t, c in sc],
                "target_columns": [f"{t}.{c}" for t, c in tc], "positives": n_pos,
                "rows": {f"{s}:{t}": len(sampled[(s, t)][1]) for s, t in sampled
                         if t in (src_tables if s == "source" else tgt_tables)}}
        (folder / "provenance.json").write_text(json.dumps(prov, indent=1), encoding="utf-8")
        stats.append((folder.relative_to(OUT).as_posix(), len(sc), len(tc), n_pos))

    pairs = sorted({(src_map[s][0], tgt_map[o][0]) for s, o in kept_pos})
    for seed in SEEDS:
        rng = random.Random(seed)
        sampled = {}
        for (side, t), (keep, body) in sorted(data.items()):
            if t in PARENTS[side] or len(body) <= CAP:
                sampled[(side, t)] = (keep, body)
            else:
                sampled[(side, t)] = (keep, [body[i] for i in sorted(rng.sample(range(len(body)), CAP))])
        write_scenario(OUT / "SyntheaOMOP-Full" / f"s{seed}", list(cols["source"]), list(cols["target"]), sampled, seed,
                       "full")
        for st, tt in pairs:
            write_scenario(OUT / "SyntheaOMOP-Pair" / f"s{seed}_{tt}__{st}", [st], [tt], sampled, seed, "pair")
    report = {"positives_benchmark": len(pos), "positives_kept": len(kept_pos), "lost": lost, "dropped": dropped,
              "renamed_source": RENAME_SOURCE, "renamed_target": RENAME_TARGET,
              "source_tables": {t: len(c) for t, c in cols["source"].items()},
              "target_tables": {t: len(c) for t, c in cols["target"].items()}, "table_pairs": pairs,
              "scenarios": [{"id": s, "source_cols": a, "target_cols": b, "positives": p} for s, a, b, p in stats]}
    (OUT / "conversion_report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "scenarios"}, indent=1)[:3000])
    print(f"{len(stats)} scenarios written")
    return 0
