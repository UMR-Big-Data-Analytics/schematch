"""Prepare public MIMIC-IV demo 2.2 and OMOP demo 0.9 using Parciak truth.
All named columns must exist. The public demos contain linked patient instances."""

from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import shutil
from collections import Counter
from pathlib import Path

PAIR = "mimic_omop"
csv.field_size_limit(1 << 30)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_table(path: Path) -> tuple[list[str], list[list[str]]]:
    raw = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    rows = list(csv.reader(io.StringIO(raw.decode("utf-8"), newline="")))
    hdr = rows[0]
    if len(set(hdr)) != len(hdr):
        raise SystemExit(f"{path}: duplicate column names {hdr}")
    body = rows[1:]
    if any(len(r) != len(hdr) for r in body):
        raise ValueError(f"Ragged CSV: {path}")
    return hdr, body


def prepare(raw: Path, output: Path) -> int:
    RAW = raw
    OUT = output
    MIMIC = RAW / "mimic-iv-demo-2.2" / "mimic-iv-clinical-database-demo-2.2" / "hosp"
    OMOP = RAW / "mimic-iv-demo-omop-0.9" / "mimic-iv-demo-data-in-the-omop-common-data-model-0.9" / "1_omop_data_csv"
    TRUTH = RAW / "parciak_ground_truth.csv"
    with TRUTH.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh))
    names = {"source": {}, "target": {}}
    for r in rows:
        for side, key, schema in (("source", "source", "MIMIC"), ("target", "target", "OMOP")):
            sch, tab, col = r[key].strip().split(".")
            if sch != schema:
                raise SystemExit(f"truth row {r}: expected {schema}")
            names[side][tab.lower()] = tab
    data = {}
    for side, folder, ext in (("source", MIMIC, ".csv.gz"), ("target", OMOP, ".csv")):
        for t in sorted(names[side]):
            p = folder / f"{t}{ext}"
            if not p.exists():
                raise SystemExit(f"{side} table {t} not found at {p}")
            data[(side, t)] = read_table(p) + (p,)
    truth = []
    for r in rows:
        s = r["source"].strip().split(".")[1:]
        t = r["target"].strip().split(".")[1:]
        s, t = (s[0].lower(), s[1]), (t[0].lower(), t[1])
        for side, (tab, col) in (("source", s), ("target", t)):
            if col not in data[(side, tab)][0]:
                raise SystemExit(f"truth names {side} column {tab}.{col}, which is not in the imported table")
        truth.append((s, t))
    dup = len(truth) - len(set(truth))
    truth = set(truth)
    if OUT.exists():
        raise FileExistsError(f"Refusing to overwrite {OUT}")
    folder = OUT / PAIR / "full"
    src = sorted(t for side, t in data if side == "source")
    tgt = sorted(t for side, t in data if side == "target")
    for side, tabs in (("source", src), ("target", tgt)):
        (folder / side).mkdir(parents=True, exist_ok=True)
        for t in tabs:
            hdr, body, _ = data[(side, t)]
            with (folder / side / f"{t}.csv").open("w", encoding="utf-8", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(hdr)
                w.writerows(body)
    sc = [(t, c) for t in src for c in data[("source", t)][0]]
    tc = [(t, c) for t in tgt for c in data[("target", t)][0]]
    (folder / "ground_truth").mkdir()
    with (folder / "ground_truth_full.csv").open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        for s in sc:
            w.writerow([1 if (s, t) in truth else 0 for t in tc])
    for st in src:
        for tt in tgt:
            with (folder / "ground_truth" / f"{st}___{tt}.csv").open("w", encoding="utf-8", newline="") as fh:
                w = csv.writer(fh)
                for c in data[("source", st)][0]:
                    w.writerow([1 if ((st, c), (tt, d)) in truth else 0 for d in data[("target", tt)][0]])
    n_pos = sum(1 for s in sc for t in tc if (s, t) in truth)
    assert n_pos == len(truth), (n_pos, len(truth))
    fan_s = Counter(s for s, _ in truth)
    fan_t = Counter(t for _, t in truth)
    structure = {"truth_rows": len(rows), "duplicate_rows": dup, "positives": n_pos,
                 "source_columns_with_truth": len(fan_s), "target_columns_with_truth": len(fan_t),
                 "source_columns_mapped_to_several_targets": sum(1 for v in fan_s.values() if v > 1),
                 "target_columns_mapped_from_several_sources": sum(1 for v in fan_t.values() if v > 1)}
    tab_pairs = Counter((s[0], t[0]) for s, t in truth)
    structure["table_pairs_with_truth"] = {f"{a}___{b}": n for (a, b), n in sorted(tab_pairs.items())}
    prov = {"kind": "full", "pair": PAIR, "truth_file": "data/external/mimic/parciak_ground_truth.csv", "truth_rows": "all",
            "instances": "linked (the same patients; the OMOP tables are an ETL of the MIMIC demo)",
            "source_columns": [f"{t}.{c}" for t, c in sc], "target_columns": [f"{t}.{c}" for t, c in tc],
            "positives": n_pos, "rows": {f"{s}:{t}": len(data[(s, t)][1]) for s, t in data}}
    (folder / "provenance.json").write_text(json.dumps(prov, indent=1), encoding="utf-8")
    manifest = {"importer_sha256": sha(Path(__file__)), "source": str(RAW), "pairs": {PAIR: {
        "source_tables": len(src), "target_tables": len(tgt), "source_columns": len(sc), "target_columns": len(tc),
        **structure, "truth_csv_sha256": sha(TRUTH),
        "ground_truth_full_sha256": sha(folder / "ground_truth_full.csv"),
        "raw_tables_sha256": {f"{side}:{t}": sha(v[2]) for (side, t), v in sorted(data.items())},
        "rows": prov["rows"]}}}
    (OUT / "import_manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(f"{PAIR}: {len(src)} x {len(tgt)} tables, {len(sc)} x {len(tc)} columns, {n_pos} positives "
          f"({len(rows)} truth rows, {dup} duplicate)")
    print(json.dumps({k: v for k, v in structure.items()}, indent=1))
    print("rows:", prov["rows"])
    return 0
