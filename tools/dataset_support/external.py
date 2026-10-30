"""T2D-SM, HDXSM and GDC-SM conversion policies, packaged from the study.
Inputs are caller-supplied paths; conversion notes and hashes are retained."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import shutil
import sys
from pathlib import Path

from .xlsx_read import read_xlsx

DS_NAME = {"t2dsm": "T2DSM", "hdxsm": "HDXSM", "gdcsm": "GDCSM"}


def read_csv(p: Path) -> tuple[list[str], list[list[str]]]:
    raw = p.read_bytes()
    for enc in ("utf-8-sig", "latin-1"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    rows = list(csv.reader(io.StringIO(text, newline="")))
    return (rows[0], rows[1:]) if rows else ([], [])


def read_dicts(path: Path, encoding="utf-8"):
    with path.open(encoding=encoding, newline="") as stream:
        return list(csv.DictReader(stream))


def dedupe(header: list[str]) -> list[str]:
    seen, out = {}, []
    for h in header:
        h = h.strip()
        seen[h] = seen.get(h, 0) + 1
        out.append(h if seen[h] == 1 else f"{h}__{seen[h]}")
    return out


def drop_empty(header, rows):
    keep = [j for j, h in enumerate(header) if h.strip() or any(j < len(r) and r[j].strip() for r in rows)]
    return [header[j] if header[j].strip() else f"col{j}" for j in keep], [[r[j] if j < len(r) else "" for j in keep] for r in rows]


def write_scenario(root: Path, sc: str, sh, sr, th, tr, matches: list[tuple[str, str]], man: dict) -> None:
    d = root / sc
    d.mkdir(parents=True, exist_ok=True)
    files = {}
    for side, h, r in (("source", sh, sr), ("target", th, tr)):
        f = d / f"{sc}_{side}.csv"
        with f.open("w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh, lineterminator="\n")
            w.writerow(h)
            w.writerows(r)
        files[f.name] = hashlib.sha256(f.read_bytes()).hexdigest()
    m = {"matches": [{"source_table": f"{sc}_source", "source_column": a, "target_table": f"{sc}_target", "target_column": b}
                     for a, b in matches]}
    f = d / f"{sc}_mapping.json"
    f.write_text(json.dumps(m, indent=2), encoding="utf-8")
    files[f.name] = hashlib.sha256(f.read_bytes()).hexdigest()
    man["scenarios"][sc] = {"src_cols": len(sh), "tgt_cols": len(th), "src_rows": len(sr), "tgt_rows": len(tr),
                            "matches": len(matches), "sha256": files}


def t2dsm(root: Path, man: dict, base: Path) -> None:
    for split in ("train", "valid", "test"):
        corr = read_dicts(base / split / f"{split}_correspondences.csv")
        by = {}
        for r in corr:
            by.setdefault(r["table_name"], []).append(r)
        for sc, rs in sorted(by.items()):
            sh, sr = read_csv(base / split / "webtables" / f"{sc}.csv")
            th, tr = read_csv(base / split / "dbpedia_tables" / f"{sc}.csv")
            sh, th = dedupe(sh), dedupe(th)
            matches = []
            ok = True
            for r in rs:
                if r["label"].strip() != "1":
                    continue
                i, j = int(r["column_index_left"]), int(r["column_index_right"])
                if i >= len(sh) or j >= len(th):
                    ok = False
                    break
                matches.append((sh[i], th[j]))
            if not ok or not matches:
                man["skipped"].append(f"{split}/{sc}: {'index out of range' if not ok else 'no positive'}")
                continue
            write_scenario(root, sc, sh, sr, th, tr, matches, man)
            man["scenarios"][sc]["split"] = split


MAP_RE = re.compile(r'^<"(.*)",\s*"(.*)">\s*$')


def hdxsm(root: Path, man: dict, base: Path) -> None:
    for d in sorted(base.iterdir(), key=lambda p: int(p.name.split("_")[1]) if p.name.startswith("pair_") else 0):
        if not d.is_dir():
            continue
        sh, sr = read_csv(d / "Table1.csv")
        th, tr = read_csv(d / "Table2.csv")
        sh, sr = drop_empty(sh, sr)
        th, tr = drop_empty(th, tr)
        sh, th = dedupe(sh), dedupe(th)
        matches, missing = [], []
        norm = lambda s: " ".join(s.replace("\\n", "\n").split())  # noqa: E731  (escaped newlines, whitespace)
        ns, nt = {norm(h): h for h in reversed(sh)}, {norm(h): h for h in reversed(th)}
        for line in (d / "mappings.txt").read_text(encoding="utf-8", errors="replace").splitlines():
            m = MAP_RE.match(line.strip())
            if not m:
                continue
            a, b = norm(m.group(1)), norm(m.group(2))
            if a in ns and b in nt:
                matches.append((ns[a], nt[b]))
            else:
                missing.append((a, b))
        if not matches:
            man["skipped"].append(f"{d.name}: no truth column found ({len(missing)} missing)")
            continue
        if missing:   # HDXSM's own truth names a column absent from its table: dropped and recorded
            man["notes"].append(f"{d.name}: {len(missing)} truth correspondence(s) name an absent column, dropped: {missing}")
        write_scenario(root, d.name, sh, sr, th, tr, matches, man)


def gdcsm(root: Path, man: dict, base: Path) -> None:
    info = json.loads((base / "papers_info.json").read_text(encoding="utf-8"))
    th, tr = read_csv(base / "gdc-sm-data" / "data" / "target-tables" / "gdc_unique_columns_concat_values.csv")
    th = dedupe(th)
    for paper, v in info.items():
        name = paper.replace(".csv", "")
        sheets = read_xlsx(base / "sources" / f"{name}.xlsx")
        rows = sheets.get(v["Sheet Name"])
        if rows is None:
            man["skipped"].append(f"{name}: sheet {v['Sheet Name']} not found ({list(sheets)[:5]})")
            continue
        width = max(len(r) for r in rows)
        rows = [[c if c is not None else "" for c in r] + [""] * (width - len(r)) for r in rows]
        sh, sr = rows[0], rows[1:]
        if name == "Vasaikar":
            sr = sr[1:-1]          # gdc_download.py: df.iloc[1:-1]
        sh = [h.replace(" \t", "").replace("\t", "").strip() if name == "McDermott" else h for h in sh]
        if name == "Clark":
            j = sh.index("Ethnicity_Self_Identify")
            for r in sr:
                r[j] = "White" if r[j] == "Whte" else r[j]
        if name == "Wang":
            j = sh.index("secondhand_smoke_exposure")
            rep = {"No or minimal exposure to secondhand smoke": "No", "Exposed in childhood houshold": "Yes",
                   "Exposed in current household": "Yes"}
            for r in sr:
                r[j] = rep.get(r[j], r[j])
        sh, sr = drop_empty(sh, sr)
        sh = dedupe(sh)
        gt = read_dicts(base / "gdc-sm-data" / "data" / "ground-truth" / paper, encoding="utf-8-sig")
        matches, missing = [], []
        for r in gt:
            a, b = r["original_paper_variable_names"].strip(), r["GDC_format_variable_names"].strip()
            if a in sh and b in th:
                matches.append((a, b))
            else:
                missing.append((a, b))
        man["notes"].append(f"{name}: {len(matches)} matches, {len(missing)} missing {missing[:3]}")
        if not matches:
            man["skipped"].append(f"{name}: no truth column found")
            continue
        write_scenario(root, name, sh, sr, th, tr, matches, man)
