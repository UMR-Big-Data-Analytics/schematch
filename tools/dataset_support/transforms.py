"""Deterministic recodings of prepared Schematch scenarios.

Salted MD5 value recoding, opaque labels, column permutation and the controlled
construction knobs. Every transformation is a pure function of its inputs and a
recorded seed/key; nothing is sampled from the clock. Provenance and source
hashes are in SOURCES.json; deliberate deviations are noted per function.

All functions read a prepared scenario (`source/`, `target/`, `ground_truth/`,
optional `ground_truth_full.csv` and `metadata/`) and write a new directory.
Ground truth is a headerless 0/1 matrix; labeled truth is rejected rather than
guessed, because its labels would have to be recoded as well.
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import random
import re
import shutil
import zlib
from collections import defaultdict
from pathlib import Path

csv.field_size_limit(2**31 - 1)
SIDES = ("source", "target")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def header(path: Path) -> list[str]:
    with path.open(newline="", encoding="utf-8", errors="replace") as fh:
        return next(csv.reader(fh), [])


def scenarios(root: Path) -> list[tuple[Path, Path]]:
    """(dataset, scenario) directories below a corpus root, sorted; output folders are ignored."""
    out = []
    for dataset in sorted(p for p in root.iterdir() if p.is_dir()):
        for scenario in sorted(p for p in dataset.iterdir() if p.is_dir()):
            if (scenario / "source").is_dir() and (scenario / "target").is_dir():
                out.append((dataset, scenario))
    return out


def require_plain_truth(scenario: Path) -> None:
    files = sorted((scenario / "ground_truth").glob("*.csv"))
    if (scenario / "ground_truth_full.csv").is_file():
        files.append(scenario / "ground_truth_full.csv")
    for path in files:
        first = header(path)
        if first and not all(re.fullmatch(r"[01](\.0)?", cell.strip()) for cell in first):
            raise ValueError(f"Labeled ground truth is not supported by this transformation: {path}")


def single_tables(scenario: Path) -> dict[str, Path]:
    tables = {side: sorted((scenario / side).glob("*.csv")) for side in SIDES}
    if any(len(t) != 1 for t in tables.values()):
        raise ValueError(f"{scenario}: expected one table per side; use the multi-table transformation")
    return {side: t[0] for side, t in tables.items()}


# ---------------------------------------------------------------------------
# Salted MD5 recoding (single-table).
#
# A bijective recoding and nothing else: a null cell stays the empty string and
# is never hashed (hashing nulls turns absence into a large shared category);
# source and target get different salts, so cross-instance value identity is
# removed while each side stays internally bijective; truth is copied byte for
# byte; dependency profiles carry over by relabelling, which is exact because an
# injective per-column map preserves equality partitions.
# ---------------------------------------------------------------------------

NULLS = {"", "null", "none", "nan", "na", "n/a", "-"}
DEFAULT_MD5_SEED = "smx-md5-audited-20260904"


def salt_for(master: str, dataset: str, scenario: str, side: str) -> str:
    return hashlib.sha256(f"{master}|{dataset}|{scenario}|{side}".encode()).hexdigest()


def digest(salt: str, value: str) -> str:
    return hashlib.md5((salt + value).encode("utf-8")).hexdigest()


def is_null(value: str) -> bool:
    return value.strip().lower() in NULLS


def transform_table(src: Path, dst: Path, salt: str):
    """Hash header and every non-null cell; nulls (NULLS tokens) are written as the empty string."""
    stats = defaultdict(lambda: {"nulls": 0, "distinct": set()})
    dst.parent.mkdir(parents=True, exist_ok=True)
    with src.open(newline="", encoding="utf-8", errors="replace") as fin, \
            dst.open("w", newline="", encoding="utf-8") as fout:
        reader, writer = csv.reader(fin), csv.writer(fout)
        names = next(reader, [])
        mapping = {h: digest(salt, h) for h in names}
        writer.writerow([mapping[h] for h in names])
        for row in reader:
            out = []
            for i, cell in enumerate(row[:len(names)]):
                name = names[i]
                if is_null(cell):
                    stats[name]["nulls"] += 1
                    out.append("")
                else:
                    v = cell.strip()
                    stats[name]["distinct"].add(v)
                    out.append(digest(salt, v))
            out.extend("" for _ in range(len(names) - len(out)))
            writer.writerow(out)
    return mapping, {k: {"nulls": v["nulls"], "distinct": len(v["distinct"])} for k, v in stats.items()}


def verify_table(plain: Path, enc: Path) -> list[str]:
    """A bijective recoding preserves row count and per-column null and distinct counts."""
    def profile(path):
        with path.open(newline="", encoding="utf-8", errors="replace") as fh:
            reader = csv.reader(fh)
            names = next(reader, [])
            nulls, seen, rows = [0] * len(names), [set() for _ in names], 0
            for row in reader:
                rows += 1
                for i in range(len(names)):
                    cell = row[i] if i < len(row) else ""
                    if is_null(cell):
                        nulls[i] += 1
                    else:
                        seen[i].add(cell.strip())
        return rows, nulls, [len(s) for s in seen]
    (r1, n1, d1), (r2, n2, d2) = profile(plain), profile(enc)
    problems = []
    if r1 != r2:
        problems.append(f"rows {r1} != {r2}")
    if n1 != n2:
        problems.append("null counts differ")
    if d1 != d2:
        problems.append("distinct counts differ")
    return problems


def rewrite_text(path: Path, mapping: dict) -> None:
    """Replace column names in `<table>.csv.<name>` references.

    A reference ends at `]`, `,`, whitespace or end of line (multi-column
    determinants are comma-separated). Longest name first, so a name that
    prefixes another is not partly replaced.
    """
    if not mapping:
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    names = sorted(mapping, key=len, reverse=True)
    pattern = re.compile(r"(?<=\.)(" + "|".join(re.escape(n) for n in names) + r")(?=[\],\s]|$)")
    path.write_text(pattern.sub(lambda m: mapping[m.group(1)], text), encoding="utf-8")


def rewrite_json(path: Path, mapping: dict, table_sha: str) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    spec = data.get("spec", {})
    if "table_sha256" in spec:
        spec["table_sha256"] = table_sha
    for column in spec.get("columns", []):
        label = column.get("label")
        if label in mapping:
            # original_label is overwritten too; keeping it would leak the plaintext name.
            column["label"] = mapping[label]
            column["original_label"] = mapping[label]
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def rewrite_manifest(src: Path, dst: Path, record: dict) -> None:
    """Make the scenario manifest describe the recoded tables."""
    data = json.loads(src.read_text(encoding="utf-8"))
    for side in SIDES:
        tables = record["sides"].get(side, {}).get("tables", [])
        table = data.get("tables", {}).get(side)
        if not tables or not table:
            continue
        mapping = {name: item["token"] for name, item in tables[0]["columns"].items()}
        table["sha256"] = tables[0]["output_sha256"]
        for column in table.get("columns", []):
            label = column.get("label")
            if label in mapping:
                column["label"] = mapping[label]
                column["original_label"] = mapping[label]
    dst.write_text(json.dumps(data, indent=2), encoding="utf-8")


PROFILE_ARTIFACTS = {
    "fd": ("FD_results.txt", True), "ucc": ("UCC_results.txt", True), "ind": ("inds.txt", False),
    "dc": ("LIMA_DC_results.txt", True), "fd.scored": ("fd.scored.json", True),
    "ucc.scored": ("ucc.scored.json", True), "ind.scored": ("ind.scored.json", True),
    "dc.scored": ("dc.scored.json", True)}


def refresh_profile_status(scenario: Path, table_hashes: dict[str, str]) -> None:
    """Rebind metadata/profile-status.properties to the written single-table files."""
    status = scenario / "metadata" / "profile-status.properties"
    if not status.is_file():
        return
    lines = status.read_text(encoding="utf-8").splitlines()
    values = {}
    for line in lines:
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            values[key] = value
    replacements = {}
    for side in SIDES:
        if side in table_hashes:
            replacements[f"{side}.table.sha256"] = table_hashes[side]
        for kind, (name, per_table) in PROFILE_ARTIFACTS.items():
            key = f"{side}.{kind}.sha256"
            path = scenario / "metadata" / side / (side if per_table else "") / name
            if key in values and path.is_file():
                replacements[key] = sha256_file(path)
    rewritten, seen = [], set()
    for line in lines:
        if "=" in line:
            key = line.split("=", 1)[0]
            if key in replacements:
                line = f"{key}={replacements[key]}"
                seen.add(key)
        rewritten.append(line)
    rewritten.extend(f"{key}={value}" for key, value in replacements.items() if key not in seen)
    status.write_text("\n".join(rewritten) + "\n", encoding="utf-8")


def md5_scenario(scenario: Path, out: Path, dataset: str, seed: str = DEFAULT_MD5_SEED,
                 cohort: str = "unknown") -> tuple[dict, list[str]]:
    """Salted MD5 recoding of one single-table scenario; returns (manifest record, verification problems)."""
    single_tables(scenario)
    require_plain_truth(scenario)
    out.mkdir(parents=True)
    record = {"master_seed": seed, "dataset": dataset, "scenario": scenario.name, "cohort": cohort,
              "null_policy": "reserved: nulls written as the empty string and never hashed", "sides": {}}
    problems = []
    for side in SIDES:
        salt = salt_for(seed, dataset, scenario.name, side)
        side_rec = {"salt_sha256": hashlib.sha256(salt.encode()).hexdigest(), "tables": []}
        mapping = {}
        for table in sorted((scenario / side).glob("*.csv")):
            dst = out / side / table.name
            m, stats = transform_table(table, dst, salt)
            mapping.update(m)
            problems += [f"{side} {table.name}: {p}" for p in verify_table(table, dst)]
            side_rec["tables"].append({"name": table.name, "input_sha256": sha256_file(table),
                                       "output_sha256": sha256_file(dst),
                                       "columns": {k: {"token": m[k], **stats.get(k, {})} for k in m}})
        # Profiles address columns by label, so only the labels move.
        meta_src, meta_dst = scenario / "metadata" / side, out / "metadata" / side
        if meta_src.is_dir():
            shutil.copytree(meta_src, meta_dst, dirs_exist_ok=True)
            table_sha = side_rec["tables"][0]["output_sha256"] if side_rec["tables"] else ""
            for f in meta_dst.rglob("*"):
                if f.suffix == ".txt":
                    rewrite_text(f, mapping)
                elif f.suffix == ".json":
                    rewrite_json(f, mapping, table_sha)
        record["sides"][side] = side_rec
    if (scenario / "ground_truth").is_dir():
        shutil.copytree(scenario / "ground_truth", out / "ground_truth", dirs_exist_ok=True)
    if (scenario / "ground_truth_full.csv").is_file():
        shutil.copyfile(scenario / "ground_truth_full.csv", out / "ground_truth_full.csv")
    if (scenario / "manifest.json").is_file():
        rewrite_manifest(scenario / "manifest.json", out / "manifest.json", record)
    for extra in ("profile-status.properties", "profiling.json"):
        p = scenario / "metadata" / extra
        if p.is_file():
            (out / "metadata").mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, out / "metadata" / extra)
    refresh_profile_status(out, {side: record["sides"][side]["tables"][0]["output_sha256"] for side in SIDES})
    text = json.dumps(record, indent=2, sort_keys=True)
    (out / "transform-manifest.json").write_text(text, encoding="utf-8")
    (out / "transform-manifest.sha256").write_text(hashlib.sha256(text.encode()).hexdigest() + "\n", encoding="utf-8")
    return record, problems


# ---------------------------------------------------------------------------
# Opaque labels, values kept.
#
# Only header lines change; values, and with them every cross-instance
# inclusion, are untouched. The token is unsalted, so a label shared by source
# and target stays shared; what is removed is lexical similarity between
# different labels. FD/UCC results are emptied, as in the original recipe.
# ---------------------------------------------------------------------------

def opaque_token(label: str) -> str:
    return "c" + hashlib.md5(label.encode("utf-8")).hexdigest()[:12]


def _rewrite_header(path: Path, obscure: set) -> None:
    with path.open(newline="", encoding="utf-8", errors="ignore") as fh:
        reader = csv.reader(fh)
        try:
            names = next(reader)
        except StopIteration:
            return
        rest = list(reader)
    with path.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow([opaque_token(h.strip()) if h.strip() in obscure else h for h in names])
        writer.writerows(rest)


def opaque_labels_dataset(src: Path, dst: Path, fraction: float = 1.0, seed: int = 0,
                          limit: int | None = None) -> dict:
    """Obscure a seeded share of labels in every scenario of one dataset directory.

    One random generator serves the sorted scenarios in turn, as in the original,
    so a scenario's chosen labels depend on its position in the dataset.
    Deviation: the original left metadata/profile-status.properties claiming
    complete profiles whose hashes no longer matched; it is rewritten here to
    `not_profiled`. Other metadata files (inds.txt, *.scored.json, manifest.json)
    are copied unchanged and still name the original columns.
    """
    rng = random.Random(seed)
    cases = sorted(x for x in src.iterdir() if x.is_dir())
    if limit:
        cases = cases[:limit]
    record = {}
    for scenario in cases:
        destination = dst / scenario.name
        shutil.copytree(scenario, destination)
        labels = set()
        for side in SIDES:
            for table in (destination / side).glob("*.csv"):
                with table.open(newline="", encoding="utf-8", errors="ignore") as fh:
                    labels.update(h.strip() for h in next(csv.reader(fh), []))
        ordered = sorted(labels)
        rng.shuffle(ordered)
        obscure = set(ordered[:round(fraction * len(ordered))])
        for side in SIDES:
            for table in (destination / side).glob("*.csv"):
                _rewrite_header(table, obscure)
        for name in ("FD_results.txt", "UCC_results.txt"):
            for meta in (destination / "metadata").rglob(name):
                meta.write_text("", encoding="utf-8")
        status = destination / "metadata" / "profile-status.properties"
        if status.is_file():
            status.write_text("".join(f"{side}.{kind}=not_profiled\n" for side in SIDES
                                      for kind in ("fd", "ucc", "ind", "dc")), encoding="ascii")
        record[scenario.name] = {"obscured": len(obscure), "labels": len(labels)}
    return record


# ---------------------------------------------------------------------------
# Opaque multi-table variants: `on` hashes table and column names, `md5` also
# recodes values with the MD5 primitives above.
#
# Table names get the side's salted digests assigned in the original sorted
# order, so the sorted table order, every matrix offset and the truth files stay
# byte-identical (renamed only). This keeps every column at its readable
# position: a method that breaks ties by index can recover positional
# information. Apply `permute_scenario` afterwards to remove that.
# ---------------------------------------------------------------------------

def side_maps(scenario: Path, dataset: str, side: str, seed: str):
    salt = salt_for(seed, dataset, scenario.name, side)
    tables = sorted(p.stem for p in (scenario / side).glob("*.csv"))
    tmap = dict(zip(tables, sorted(digest(salt, t) for t in tables)))   # order-preserving
    cols = {t: header(scenario / side / f"{t}.csv") for t in tables}
    cmap = {c: digest(salt, c) for t in tables for c in cols[t]}
    return salt, tmap, cmap, cols


def label_regex(tmap: dict, cols: dict, cmap: dict):
    """Pattern and replacements for one side's `table.csv.col` and `table.col` references, longest first."""
    rep = {}
    for t, names in cols.items():
        for c in names:
            rep[f"{t}.csv.{c}"] = f"{tmap[t]}.csv.{cmap[c]}"
            rep[f"{t}.{c}"] = f"{tmap[t]}.{cmap[c]}"
    keys = sorted(rep, key=len, reverse=True)
    rx = re.compile(r"(?<![^\s\[,'\"(])(" + "|".join(re.escape(k) for k in keys) + r")(?=$|[\s\],'\")])", re.M)
    return rx, rep


def relabel_text(text: str, rx, rep) -> str:
    return rx.sub(lambda m: rep[m.group(1)], text)


def relabel_json(obj, maps: dict, side: str | None = None):
    """Side-aware: keys/values below `source...` / `target...` use that side's maps."""
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            s, nk = side, k
            for sd in SIDES:
                if k.startswith(sd):
                    s = sd
                    for sep in ("/", ":"):
                        if k.startswith(sd + sep) and k[len(sd) + 1:] in maps[sd][0]:
                            nk = f"{sd}{sep}{maps[sd][0][k[len(sd) + 1:]]}"
            if side in maps and k in maps[side][0]:
                nk = maps[side][0][k]
            elif side in maps and "/" in k and k.replace("/", ".", 1) in maps[side][2]:
                nk = maps[side][2][k.replace("/", ".", 1)].replace(".", "/", 1)
            out[nk] = relabel_json(v, maps, s)
        return out
    if isinstance(obj, list):
        return [relabel_json(v, maps, side) for v in obj]
    if isinstance(obj, str) and side in maps:
        tmap, rx, rep = maps[side]
        return tmap[obj] if obj in tmap else relabel_text(obj, rx, rep)
    return obj


def opaque_tables_scenario(scenario: Path, dst: Path, dataset: str, variant: str,
                           seed: str = DEFAULT_MD5_SEED) -> dict:
    if variant not in ("on", "md5"):
        raise ValueError("variant must be 'on' or 'md5'")
    require_plain_truth(scenario)
    maps, rec = {}, {"sides": {}}
    for side in SIDES:
        salt, tmap, cmap, cols = side_maps(scenario, dataset, side, seed)
        if len(set(tmap.values())) != len(tmap) or len(set(cmap.values())) != len(cmap):
            raise ValueError(f"{scenario} {side}: label map is not injective")
        rx, rep = label_regex(tmap, cols, cmap)
        maps[side] = (tmap, rx, rep)
        (dst / side).mkdir(parents=True)
        for t in tmap:
            src_t, dst_t = scenario / side / f"{t}.csv", dst / side / f"{tmap[t]}.csv"
            if variant == "md5":
                transform_table(src_t, dst_t, salt)
                problems = verify_table(src_t, dst_t)
                if problems:
                    raise ValueError(f"{scenario} {side} {t}: {problems}")
            else:
                with src_t.open(newline="", encoding="utf-8", errors="replace") as fin, \
                        dst_t.open("w", newline="", encoding="utf-8") as fout:
                    r, w = csv.reader(fin), csv.writer(fout)
                    w.writerow([cmap[h] for h in next(r)])
                    for row in r:
                        w.writerow(row)
        msrc = scenario / "metadata" / side
        if msrc.is_dir():
            for f in msrc.rglob("*"):
                if f.is_dir():
                    continue
                rel = tuple(tmap.get(p, p) for p in f.relative_to(msrc).parts)
                o = dst / "metadata" / side / Path(*rel)
                o.parent.mkdir(parents=True, exist_ok=True)
                if f.suffix in (".txt", ".json"):
                    text = f.read_text(encoding="utf-8")
                    o.write_text(relabel_text(text, rx, rep) if f.suffix == ".txt"
                                 else json.dumps(relabel_json(json.loads(text), {side: maps[side]}, side), indent=1),
                                 encoding="utf-8")
                else:
                    shutil.copyfile(f, o)
        rec["sides"][side] = {"salt_sha256": hashlib.sha256(salt.encode()).hexdigest(), "tables": dict(tmap)}
    (dst / "ground_truth").mkdir()
    for f in sorted((scenario / "ground_truth").glob("*.csv")):
        s, t = f.stem.split("___")
        shutil.copyfile(f, dst / "ground_truth" / f"{maps['source'][0][s]}___{maps['target'][0][t]}.csv")
    if (scenario / "ground_truth_full.csv").is_file():
        shutil.copyfile(scenario / "ground_truth_full.csv", dst / "ground_truth_full.csv")
    for name in ("provenance.json", "metadata/multitable_profile.json"):
        if (scenario / name).is_file():
            obj = relabel_json(json.loads((scenario / name).read_text(encoding="utf-8")), maps)
            (dst / name).parent.mkdir(parents=True, exist_ok=True)
            (dst / name).write_text(json.dumps(obj, indent=1), encoding="utf-8")
    _verify_opaque(scenario, dst, maps)
    return rec


def _verify_opaque(scenario: Path, dst: Path, maps: dict) -> None:
    for side in SIDES:
        tmap = maps[side][0]
        a = sorted(p.stem for p in (scenario / side).glob("*.csv"))
        b = sorted(p.stem for p in (dst / side).glob("*.csv"))
        if [tmap[t] for t in a] != b:
            raise ValueError(f"{scenario} {side}: table order not preserved")
        for t in a:
            if len(header(scenario / side / f"{t}.csv")) != len(header(dst / side / f"{tmap[t]}.csv")):
                raise ValueError(f"{scenario} {side} {t}: width changed")
    for f in (scenario / "ground_truth").glob("*.csv"):
        s, t = f.stem.split("___")
        g = dst / "ground_truth" / f"{maps['source'][0][s]}___{maps['target'][0][t]}.csv"
        if g.read_bytes() != f.read_bytes():
            raise ValueError(f"truth changed: {g}")
    for f in dst.rglob("*"):
        if f.is_file() and f.suffix in (".txt", ".json"):
            text = f.read_text(encoding="utf-8")
            for side in SIDES:
                m = maps[side][1].search(text)
                if m is not None:
                    raise ValueError(f"{f}: original label {m.group(1)!r} remains")


# ---------------------------------------------------------------------------
# Column permutation.
#
# Per side and table, random.Random(SHA-256 hex of a key) shuffles the column
# order, redrawn until it differs from the original (tables with >= 2 columns).
# Truth blocks, the full truth matrix and score matrices are permuted alike;
# label-addressed profiles are unchanged, while JSON column specs receive their
# new positions and table hashes.
# ---------------------------------------------------------------------------

DEFAULT_PERMUTATION_KEY = "{dataset}/{scenario}/{side}/{table}"


def permutation(n: int, key: str) -> list[int]:
    rng = random.Random(hashlib.sha256(key.encode()).hexdigest())
    order = list(range(n))
    if n < 2:
        return order
    while order == list(range(n)):
        rng.shuffle(order)
    return order


def _read_rows(path: Path) -> list[list[str]]:
    return list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"), newline="")))


def _terminator(path: Path) -> str:
    with path.open("rb") as fh:
        return "\r\n" if b"\r\n" in fh.read(1 << 16) else "\n"


def permute_table(src: Path, dst: Path, order: list[int]) -> None:
    rows = _read_rows(src)
    width = len(rows[0])
    dst.parent.mkdir(parents=True, exist_ok=True)
    with dst.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        for r in rows:
            r = (r + [""] * (width - len(r)))[:width]
            w.writerow([r[j] for j in order])


def permute_matrix(rows: list[list[str]], source_order: list[int], target_order: list[int],
                   labeled: bool = True) -> list[list[str]]:
    """Reorder a score/truth matrix to permuted tables; a labeled matrix keeps its first row/column as labels."""
    if not labeled:
        return [[rows[i][j] for j in target_order] for i in source_order]
    head, body = rows[0], rows[1:]
    if len(head) - 1 != len(target_order) or len(body) != len(source_order):
        raise ValueError("matrix shape does not match the tables")
    return [[head[0]] + [head[1 + j] for j in target_order]] + \
        [[body[i][0]] + [body[i][1 + j] for j in target_order] for i in source_order]


def _write_rows(path: Path, rows, terminator: str = "\n") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        csv.writer(fh, lineterminator=terminator).writerows(rows)


def scenario_orders(scenario: Path, dataset: str, key: str = DEFAULT_PERMUTATION_KEY) -> dict:
    """{side: {table stem: column order}} for a scenario, from the key template."""
    out = {}
    for side in SIDES:
        out[side] = {}
        for table in sorted((scenario / side).glob("*.csv")):
            k = key.format(dataset=dataset, scenario=scenario.name, side=side, table=table.stem)
            out[side][table.stem] = permutation(len(header(table)), k)
    return out


def global_order(orders: dict[str, list[int]]) -> list[int]:
    """Concatenate per-table orders (sorted table names) into one scenario-wide column order."""
    out, offset = [], 0
    for stem in sorted(orders):
        out += [offset + j for j in orders[stem]]
        offset += len(orders[stem])
    return out


def permute_scenario(scenario: Path, dst: Path, dataset: str, key: str = DEFAULT_PERMUTATION_KEY) -> dict:
    require_plain_truth(scenario)
    orders = scenario_orders(scenario, dataset, key)
    shutil.copytree(scenario, dst, ignore=shutil.ignore_patterns("*.csv"))
    hashes = {}
    for side in SIDES:
        for stem, order in orders[side].items():
            permute_table(scenario / side / f"{stem}.csv", dst / side / f"{stem}.csv", order)
            hashes[(side, stem)] = sha256_file(dst / side / f"{stem}.csv")
    for f in sorted((scenario / "ground_truth").glob("*.csv")):
        s, t = f.stem.split("___")
        rows = permute_matrix(_read_rows(f), orders["source"][s], orders["target"][t], labeled=False)
        _write_rows(dst / "ground_truth" / f.name, rows, _terminator(f))
    full = scenario / "ground_truth_full.csv"
    if full.is_file():
        rows = permute_matrix(_read_rows(full), global_order(orders["source"]),
                              global_order(orders["target"]), labeled=False)
        _write_rows(dst / full.name, rows, _terminator(full))
    # Copy any other CSV below metadata/ unchanged (only tables and truth are positional).
    for f in scenario.rglob("*.csv"):
        target = dst / f.relative_to(scenario)
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, target)
    for side in SIDES:
        for stem, order in orders[side].items():
            position = {old: new for new, old in enumerate(order)}
            for f in (dst / "metadata" / side / stem).glob("*.json") if (dst / "metadata" / side / stem).is_dir() else []:
                data = json.loads(f.read_text(encoding="utf-8"))
                spec = data.get("spec", {})
                if "columns" in spec:
                    for column in spec["columns"]:
                        column["index"] = position[column["index"]]
                    spec["table_sha256"] = hashes[(side, stem)]
                    f.write_text(json.dumps(data, indent=2), encoding="utf-8")
    manifest = dst / "manifest.json"
    if manifest.is_file():
        data = json.loads(manifest.read_text(encoding="utf-8"))
        for side in SIDES:
            table = data.get("tables", {}).get(side)
            if table and len(orders[side]) == 1:
                stem, order = next(iter(orders[side].items()))
                position = {old: new for new, old in enumerate(order)}
                table["sha256"] = hashes[(side, stem)]
                for column in table.get("columns", []):
                    column["index"] = position[column["index"]]
        manifest.write_text(json.dumps(data, indent=2), encoding="utf-8")
    single = {side: hashes[(side, next(iter(orders[side])))] for side in SIDES if len(orders[side]) == 1}
    refresh_profile_status(dst, single)
    return orders


# ---------------------------------------------------------------------------
# Controlled construction knobs on single-table scenarios (target side only).
#   base    unchanged copy
#   K1      target columns in a seeded order; truth columns permuted accordingly
#   K2p<p>  a share p of the target's distinct values recoded to fresh tokens, one
#           mapping per side, so FDs, UCCs and within-side INDs still hold. Nested.
#   K3p<p>  a share p of the target's labels replaced by tokens; profiles relabelled. Nested.
#   K4f<f>  each FD file thinned to a seeded share f of its lines.
# Profile hashes in profile-status.properties are recomputed for every changed file.
# ---------------------------------------------------------------------------

KNOB_PS = (0.25, 0.5, 0.75, 1.0)
KNOB_FS = (0.5, 0.25)
KNOB_PROFILE_FILES = {"FD_results.txt": "fd", "UCC_results.txt": "ucc", "inds.txt": "ind", "LIMA_DC_results.txt": "dc"}


def frac(key: str) -> float:
    return (zlib.crc32(key.encode("utf-8")) % 100000) / 100000.0


def knob_names() -> list[str]:
    return ["base", "K1"] + [f"K2p{int(p * 100)}" for p in KNOB_PS] + \
        [f"K3p{int(p * 100)}" for p in KNOB_PS] + [f"K4f{int(f * 100)}" for f in KNOB_FS]


def _knob_read(p: Path):
    with p.open(encoding="utf-8", newline="") as fh:
        rows = list(csv.reader(fh))
    return rows[0], rows[1:]


def _knob_write(p: Path, names, rows) -> None:
    with p.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(names)
        w.writerows(rows)


def _knob_rehash(scen: Path) -> None:
    st = scen / "metadata" / "profile-status.properties"
    if not st.is_file():
        return
    new = {}
    for side in SIDES:
        t = next((scen / side).glob("*.csv"), None)
        if t is not None:
            new[f"{side}.table.sha256"] = sha256_file(t)
        for fname, kind in KNOB_PROFILE_FILES.items():
            files = sorted((scen / "metadata" / side).rglob(fname))
            if files:
                new[f"{side}.{kind}.sha256"] = sha256_file(files[0]) if len(files) == 1 else \
                    hashlib.sha256(b"".join(f.read_bytes() for f in files)).hexdigest()
    out = []
    for line in st.read_text(encoding="utf-8").splitlines():
        k = line.split("=", 1)[0]
        out.append(f"{k}={new[k]}" if k in new and "=" in line else line)
    st.write_text("\n".join(out) + "\n", encoding="utf-8")


def _knob_rename_labels(scen: Path, mapping: dict[str, str], all_names: list[str]) -> None:
    """Rewrite `target.csv.<name>` labels; every target name is in the pattern (longest first)
    so a name that prefixes another is never matched inside it."""
    if not mapping:
        return
    alt = "|".join(re.escape(k) for k in sorted(all_names, key=len, reverse=True))
    pat = re.compile(r"target\.csv\.(" + alt + r")(?=[\],)\"]|\s\(|\s*$|,|\s*\])")
    lab = re.compile(r'("label":\s*")(' + alt + r')(")')
    for f in (scen / "metadata").rglob("*"):
        if f.is_file() and f.suffix in (".txt", ".json"):
            t = f.read_text(encoding="utf-8")
            t2 = pat.sub(lambda m: "target.csv." + mapping.get(m.group(1), m.group(1)), t)
            if f.suffix == ".json" and f.parent.name == "target":
                t2 = lab.sub(lambda m: m.group(1) + mapping.get(m.group(2), m.group(2)) + m.group(3), t2)
            if t2 != t:
                f.write_text(t2, encoding="utf-8")


def knob_scenario(src: Path, dst: Path, knob: str, key: str) -> str:
    """Write one knob variant of a single-table scenario; returns the target table's SHA-256.

    `key` identifies the case in every seeded decision (the original used
    `x79|<dataset>|<scenario>`). K1 does not renumber JSON column specs, as in
    the original recipe; use `permute_scenario` for a fully reindexed copy.
    """
    if knob not in knob_names():
        raise ValueError(f"Unknown knob {knob}; choose from {knob_names()}")
    single_tables(src)
    shutil.copytree(src, dst)
    tgt = next((dst / "target").glob("*.csv"))
    gt_file = next((dst / "ground_truth").glob("*.csv"))
    if knob == "K1":
        names, rows = _knob_read(tgt)
        order = sorted(range(len(names)), key=lambda j: frac(f"{key}|K1|{j}"))
        _knob_write(tgt, [names[j] for j in order], [[r[j] if j < len(r) else "" for j in order] for r in rows])
        with gt_file.open(encoding="utf-8", newline="") as fh:
            g = list(csv.reader(fh))
        with gt_file.open("w", encoding="utf-8", newline="") as fh:
            csv.writer(fh, lineterminator="\n").writerows([[r[j] for j in order] for r in g])
    elif knob.startswith("K2"):
        p = int(knob[3:]) / 100
        names, rows = _knob_read(tgt)
        code = {}

        def rec(v):
            if v == "" or frac(f"{key}|K2|{v}") >= p:
                return v
            if v not in code:
                code[v] = "v" + hashlib.sha1(f"{key}|K2tok|{v}".encode()).hexdigest()[:14]
            return code[v]
        _knob_write(tgt, names, [[rec(v) for v in r] for r in rows])
    elif knob.startswith("K3"):
        p = int(knob[3:]) / 100
        names, rows = _knob_read(tgt)
        mapping = {h: "c" + hashlib.sha1(f"{key}|K3tok|{h}".encode()).hexdigest()[:10]
                   for h in names if frac(f"{key}|K3|{h}") < p}
        _knob_write(tgt, [mapping.get(h, h) for h in names], rows)
        _knob_rename_labels(dst, mapping, names)
    elif knob.startswith("K4"):
        f = int(knob[3:]) / 100
        for fd in (dst / "metadata").rglob("FD_results.txt"):
            lines = [line for line in fd.read_text(encoding="utf-8").splitlines() if line.strip()]
            kept = [line for line in lines if frac(f"{key}|K4|{fd.parent.name}|{line}") < f]
            fd.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
    _knob_rehash(dst)
    return sha256_file(tgt)
