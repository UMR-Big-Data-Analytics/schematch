"""Audited, deterministic MusicBrainz/Lahman Valentine-style benchmarks.

The external releases are read-only. ``prepare`` first builds bounded joined
views with declared relational provenance, then fabricates the four Valentine
relationship types under a plain and a domain-specific structural regime.
No matcher or ground-truth-derived statistic is consulted during generation.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import heapq
import json
import math
import os
import shutil
import tempfile
from pathlib import Path

from .lossless import digest, json_write

VERSION = 2
MB_HEADERS = {
    "medium": ["id", "release", "position", "format", "name", "edits_pending",
               "last_updated", "track_count"],
    "release": ["id", "gid", "name", "artist_credit", "release_group", "status",
                "packaging", "language", "script", "barcode", "comment", "edits_pending",
                "quality", "last_updated"],
    "release_group": ["id", "gid", "name", "artist_credit", "type", "comment",
                      "edits_pending", "last_updated"],
}
MB_COLUMNS = [
    ("medium.id", "integer"), ("medium.release", "integer"),
    ("medium.position", "integer"), ("medium.format", "integer"),
    ("medium.name", "string"), ("medium.track_count", "integer"),
    ("release.gid", "string"), ("release.name", "string"),
    ("release.artist_credit", "integer"), ("release.release_group", "integer"),
    ("release.status", "integer"), ("release.packaging", "integer"),
    ("release.language", "integer"), ("release.script", "integer"),
    ("release.barcode", "string"), ("release.quality", "integer"),
    ("release_group.gid", "string"), ("release_group.name", "string"),
    ("release_group.artist_credit", "integer"), ("release_group.type", "integer"),
]
LAHMAN_COLUMNS = [
    ("batting.playerID", "string"), ("batting.yearID", "integer"),
    ("batting.stint", "integer"), ("batting.teamID", "string"),
    ("batting.lgID", "string"), ("batting.G", "integer"),
    ("batting.AB", "integer"), ("batting.R", "integer"),
    ("batting.H", "integer"), ("batting.2B", "integer"),
    ("batting.3B", "integer"), ("batting.HR", "integer"),
    ("batting.RBI", "integer"), ("batting.SB", "integer"),
    ("batting.CS", "integer"), ("batting.BB", "integer"),
    ("batting.SO", "integer"), ("batting.HBP", "integer"),
    ("batting.SF", "integer"), ("batting.GIDP", "integer"),
    ("people.birthYear", "integer"), ("people.birthCountry", "string"),
    ("people.nameFirst", "string"), ("people.nameLast", "string"),
    ("people.weight", "integer"), ("people.height", "integer"),
    ("people.bats", "string"), ("people.throws", "string"),
    ("people.debut", "date"), ("teams.franchID", "string"),
    ("teams.divID", "string"), ("teams.Rank", "integer"),
    ("teams.W", "integer"), ("teams.L", "integer"),
    ("teams.name", "string"), ("teams.park", "string"),
    ("teams.attendance", "integer"), ("teams.BPF", "integer"),
    ("teams.PPF", "integer"),
]


def safe_label(qualified: str) -> str:
    """Keep relation provenance without triggering legacy table.column parsing."""
    return qualified.replace(".", "__")


def opaque_label(seed: int, dataset: str, kind: str, regime: str,
                 side: str, index: int) -> str:
    """A deterministic opaque identifier with no cross-side position leak."""
    key = f"{seed}\0{dataset}\0{kind}\0{regime}\0{side}\0{index}".encode()
    return f"{side[0]}_{hashlib.sha256(key).hexdigest()[:16]}"


def _rank(seed: int, key: str) -> int:
    return int.from_bytes(hashlib.sha256(f"{seed}\0{key}".encode()).digest(), "big")


def _bounded_sample(rows, size: int, seed: int, key):
    """Return the rows with the smallest stable hash ranks, independent of input order."""
    heap = []
    for row in rows:
        value = _rank(seed, key(row))
        item = (-value, key(row), row)
        if len(heap) < size:
            heapq.heappush(heap, item)
        elif item > heap[0]:
            heapq.heapreplace(heap, item)
    return [x[2] for x in sorted(heap, key=lambda x: (-x[0], x[1]))]


def _tsv_rows(path: Path, header: list[str]):
    with path.open(encoding="utf-8", newline="") as stream:
        for number, values in enumerate(csv.reader(stream, delimiter="\t", quoting=csv.QUOTE_NONE), 1):
            if len(values) != len(header):
                raise ValueError(f"{path}: row {number} has {len(values)} fields, expected {len(header)}")
            yield {name: "" if value == r"\N" else value for name, value in zip(header, values)}


def _csv_dict_rows(path: Path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or len(reader.fieldnames) != len(set(reader.fieldnames)):
            raise ValueError(f"Missing or duplicate header: {path}")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError(f"Ragged CSV: {path}, line {reader.line_num}")
            yield row


def _select(row: dict, columns: list[tuple[str, str]]) -> list[str]:
    values = []
    for qualified, _ in columns:
        table, name = qualified.split(".", 1)
        values.append(row[table][name])
    return values


def musicbrainz_view(root: Path, rows: int, seed: int):
    medium_path = root / "Musicbrainz/medium"
    release_path = root / "Musicbrainz/release"
    group_path = root / "Musicbrainz/release_group"
    media = _bounded_sample(_tsv_rows(medium_path, MB_HEADERS["medium"]), rows, seed,
                            lambda x: x["id"])
    release_ids = {x["release"] for x in media}
    releases = {x["id"]: x for x in _tsv_rows(release_path, MB_HEADERS["release"])
                if x["id"] in release_ids}
    missing_release = release_ids - releases.keys()
    if missing_release:
        raise ValueError(f"MusicBrainz has {len(missing_release)} sampled dangling medium.release values")
    group_ids = {x["release_group"] for x in releases.values() if x["release_group"]}
    groups = {x["id"]: x for x in _tsv_rows(group_path, MB_HEADERS["release_group"])
              if x["id"] in group_ids}
    missing_group = group_ids - groups.keys()
    if missing_group:
        raise ValueError(f"MusicBrainz has {len(missing_group)} sampled dangling release.release_group values")
    joined = []
    for medium in media:
        release = releases[medium["release"]]
        group = groups.get(release["release_group"], {x: "" for x in MB_HEADERS["release_group"]})
        joined.append(_select({"medium": medium, "release": release, "release_group": group}, MB_COLUMNS))
    provenance = {
        "raw_files": [{"path": str(p.resolve()), "bytes": p.stat().st_size, "sha256": digest(p)}
                      for p in (medium_path, release_path, group_path)],
        "declared_relations": [
            {"kind": "foreign_key", "dependent": "medium.release", "referenced": "release.id"},
            {"kind": "foreign_key", "dependent": "release.release_group", "referenced": "release_group.id"},
        ],
        "join": "medium JOIN release ON medium.release=release.id JOIN release_group ON release.release_group=release_group.id",
    }
    return MB_COLUMNS, joined, provenance


def lahman_view(root: Path, rows: int, seed: int):
    folder = root / "Lahman"
    batting_path, people_path, teams_path = (folder / x for x in ("Batting.csv", "People.csv", "Teams.csv"))
    people = {x["playerID"]: x for x in _csv_dict_rows(people_path)}
    teams = {(x["yearID"], x["lgID"], x["teamID"]): x for x in _csv_dict_rows(teams_path)}
    def candidates():
        for batting in _csv_dict_rows(batting_path):
            person = people.get(batting["playerID"])
            team = teams.get((batting["yearID"], batting["lgID"], batting["teamID"]))
            if person is not None and team is not None:
                yield {"batting": batting, "people": person, "teams": team}
    selected = _bounded_sample(candidates(), rows, seed,
                               lambda x: "|".join(x["batting"][k] for k in ("playerID", "yearID", "stint", "teamID")))
    joined = [_select(x, LAHMAN_COLUMNS) for x in selected]
    provenance = {
        "raw_files": [{"path": str(p.resolve()), "bytes": p.stat().st_size, "sha256": digest(p)}
                      for p in (batting_path, people_path, teams_path)],
        "declared_relations": [
            {"kind": "foreign_key", "dependent": "batting.playerID", "referenced": "people.playerID"},
            {"kind": "foreign_key", "dependent": ["batting.yearID", "batting.lgID", "batting.teamID"],
             "referenced": ["teams.yearID", "teams.lgID", "teams.teamID"]},
        ],
        "join": "Batting JOIN People USING playerID JOIN Teams USING (yearID,lgID,teamID)",
    }
    return LAHMAN_COLUMNS, joined, provenance


def _encoded(value: str, side: str, column: str) -> str:
    if value == "":
        return ""
    key = hashlib.sha256(f"controlled-benchmark-v1:{side}:{column}".encode()).digest()
    return hashlib.blake2b(value.encode(), key=key, digest_size=16).hexdigest()


def _monotone(value: str, side: str, index: int) -> str:
    if value == "":
        return ""
    try:
        number = float(value)
    except ValueError:
        return _encoded(value, side, str(index))
    scale = 3 if side == "source" else 7
    offset = 1009 + index * 17 if side == "source" else -2027 + index * 31
    transformed = number * scale + offset
    return str(int(transformed)) if transformed.is_integer() else format(transformed, ".12g")


def _validate_transform(values: list[str], side: str, index: int, transform, ordered: bool):
    """Fail if an observed structural regime changes equality or claimed order."""
    images = {}
    numeric = []
    for value in set(values):
        if value == "":
            continue
        image = transform(value, side, index)
        if image in images and images[image] != value:
            raise ValueError(f"Transform merges {images[image]!r} and {value!r} in column {index}")
        images[image] = value
        if ordered:
            try:
                numeric.append((float(value), float(image)))
            except ValueError:
                pass
    if ordered:
        pairs = sorted(set(numeric))
        if any(a[0] < b[0] and not a[1] < b[1] for a, b in zip(pairs, pairs[1:])):
            raise ValueError(f"Transform is not strictly monotone in column {index}")


def _relationship(width: int, kind: str):
    all_columns = list(range(width))
    if kind == "Unionable":
        return all_columns, all_columns, "horizontal_overlap_50pct"
    if kind == "Joinable":
        cut = math.ceil(width * .7)
        start = width - cut
        return all_columns[:cut], all_columns[start:], "vertical_overlap_40pct"
    if kind == "View-Unionable":
        target = [i for i in all_columns if i % 5 not in (3, 4)]
        return all_columns, target, "target_projection_60pct"
    if kind == "Semantically-Joinable":
        cut = math.ceil(width * .7)
        start = width - cut
        return all_columns[:cut], all_columns[start:], "vertical_40pct_and_horizontal_50pct"
    raise ValueError(kind)


def _row_slices(rows: list[list[str]], kind: str):
    if kind in ("Unionable", "View-Unionable", "Semantically-Joinable"):
        quarter = len(rows) // 4
        return rows[:len(rows) - quarter], rows[quarter:]
    return rows, rows


def _vocabulary(rows: list[list[str]]) -> set[str]:
    return {value for row in rows for value in row if value}


def _write_csv(path: Path, header: list[str], rows: list[list[str]]):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(rows)


def fabricate(dataset: str, columns: list[tuple[str, str]], rows: list[list[str]], output: Path,
              seed: int, regime: str):
    records = []
    for kind in ("Unionable", "Joinable", "View-Unionable", "Semantically-Joinable"):
        source_idx, target_idx, relation = _relationship(len(columns), kind)
        source_base, target_base = _row_slices(rows, kind)
        scenario = f"{dataset.lower()}_{kind.lower().replace('-', '_')}_{regime}"
        directory = output / "Valentine-datasets" / dataset / kind / scenario
        directory.mkdir(parents=True, exist_ok=False)
        if regime == "plain":
            source_header = [safe_label(columns[i][0]) for i in source_idx]
            target_header = [safe_label(columns[i][0]) for i in target_idx]
            transform = lambda value, side, index: value
        elif regime == "independent-recode":
            source_header = [opaque_label(seed, dataset, kind, regime, "source", i) for i in source_idx]
            target_header = [opaque_label(seed, dataset, kind, regime, "target", i) for i in target_idx]
            transform = lambda value, side, index: _encoded(value, side, columns[index][0])
        elif regime == "monotone-numeric":
            source_header = [opaque_label(seed, dataset, kind, regime, "source", i) for i in source_idx]
            target_header = [opaque_label(seed, dataset, kind, regime, "target", i) for i in target_idx]
            transform = lambda value, side, index: (_monotone(value, side, index)
                                                     if columns[index][1] == "integer"
                                                     else _encoded(value, side, columns[index][0]))
        else:
            raise ValueError(regime)
        if regime != "plain":
            for side, base, indices in (("source", source_base, source_idx),
                                        ("target", target_base, target_idx)):
                for index in indices:
                    _validate_transform([row[index] for row in base], side, index, transform,
                                        regime == "monotone-numeric" and columns[index][1] == "integer")
        source_rows = [[transform(row[i], "source", i) for i in source_idx] for row in source_base]
        target_rows = [[transform(row[i], "target", i) for i in target_idx] for row in target_base]
        _write_csv(directory / f"{scenario}_source.csv", source_header, source_rows)
        _write_csv(directory / f"{scenario}_target.csv", target_header, target_rows)
        common = sorted(set(source_idx) & set(target_idx))
        matches = [{"source_table": scenario + "_source", "source_column": source_header[source_idx.index(i)],
                    "target_table": scenario + "_target", "target_column": target_header[target_idx.index(i)]}
                   for i in common]
        json_write(directory / f"{scenario}_mapping.json", {"matches": matches})
        for side, header, indices in (("source", source_header, source_idx), ("target", target_header, target_idx)):
            json_write(directory / f"{scenario}_{side}.json",
                       {label: {"type": columns[index][1]} for label, index in zip(header, indices)})
        sv, tv = _vocabulary(source_rows), _vocabulary(target_rows)
        design = {
            "version": VERSION,
            "scenario_id": f"valentine/{dataset}/{kind}/{scenario}/{scenario}",
            "dataset": dataset, "scenario_type": kind, "evidence_regime": regime,
            "seed": seed, "base_rows": len(rows), "relationship_transform": relation,
            "source_rows": len(source_rows), "target_rows": len(target_rows),
            "source_width": len(source_idx), "target_width": len(target_idx),
            "ground_truth_pairs": len(common),
            "source_gt_fraction": len(common) / len(source_idx),
            "target_gt_fraction": len(common) / len(target_idx),
            "numeric_or_temporal_fraction": sum(t in ("integer", "date") for _, t in columns) / len(columns),
            "cross_side_vocabulary_jaccard": len(sv & tv) / len(sv | tv) if sv | tv else 1.0,
            "generator": "smx.controlled", "matcher_outcomes_consulted": False,
        }
        json_write(directory / f"{scenario}_design.json", design)
        records.append(design)
    return records


def prepare(raw_root: Path, output: Path, rows: int, seed: int):
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".controlled-", dir=output.parent))
    try:
        datasets = []
        for name, builder, regime in (("MusicBrainz", musicbrainz_view, "independent-recode"),
                                      ("Lahman", lahman_view, "monotone-numeric")):
            columns, joined, provenance = builder(raw_root, rows, seed)
            base = stage / "base"
            base.mkdir(exist_ok=True)
            base_path = base / f"{name.lower()}.csv"
            _write_csv(base_path, [x[0] for x in columns], joined)
            designs = fabricate(name, columns, joined, stage, seed, "plain")
            designs += fabricate(name, columns, joined, stage, seed, regime)
            datasets.append({"dataset": name, "rows": len(joined), "columns": len(columns),
                             "base_view": {"path": str(base_path.relative_to(stage)),
                                           "sha256": digest(base_path)},
                             "provenance": provenance, "scenarios": designs})
        manifest = {"version": VERSION, "raw_root": str(raw_root.resolve()), "seed": seed,
                    "sample_size": rows, "datasets": datasets,
                    "protocol": "a-priori controlled design; no matcher outcomes consulted"}
        json_write(stage / "controlled-benchmark.json", manifest)
        stage.rename(output)
        return output / "controlled-benchmark.json"
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def audit(raw_root: Path):
    checks = []
    for relative in ("Musicbrainz/medium", "Musicbrainz/release", "Musicbrainz/release_group",
                     "Lahman/Batting.csv", "Lahman/People.csv", "Lahman/Teams.csv"):
        path = raw_root / relative
        checks.append({"path": str(path.resolve()), "exists": path.is_file(),
                       **({"bytes": path.stat().st_size} if path.is_file() else {})})
    return {"version": VERSION, "raw_root": str(raw_root.resolve()), "checks": checks,
            "summary": {"required": len(checks), "present": sum(x["exists"] for x in checks)}}


def _partition(values: list[str]):
    ids = {}
    return [ids.setdefault(value, len(ids)) for value in values]


def verify(output: Path):
    """Verify observed equality/order preservation between paired generated regimes."""
    manifest = json.loads((output / "controlled-benchmark.json").read_text(encoding="utf-8"))
    checks, errors = [], []
    for dataset in manifest["datasets"]:
        name = dataset["dataset"]
        columns = {safe_label(column): kind for column, kind in
                   (MB_COLUMNS if name == "MusicBrainz" else LAHMAN_COLUMNS)}
        regimes = sorted({x["evidence_regime"] for x in dataset["scenarios"] if x["evidence_regime"] != "plain"})
        if len(regimes) != 1:
            errors.append(f"{name}: expected one structural regime")
            continue
        regime = regimes[0]
        for kind in ("Unionable", "Joinable", "View-Unionable", "Semantically-Joinable"):
            stems = {"plain": f"{name.lower()}_{kind.lower().replace('-', '_')}_plain",
                     regime: f"{name.lower()}_{kind.lower().replace('-', '_')}_{regime}"}
            folders = {r: output / "Valentine-datasets" / name / kind / stem for r, stem in stems.items()}
            for side in ("source", "target"):
                tables = {}
                for r, folder in folders.items():
                    path = folder / f"{stems[r]}_{side}.csv"
                    with path.open(encoding="utf-8", newline="") as stream:
                        tables[r] = list(csv.reader(stream))
                plain, transformed = tables["plain"], tables[regime]
                if len(plain) != len(transformed) or len(plain[0]) != len(transformed[0]):
                    errors.append(f"{name}/{kind}/{side}: shape changed")
                    continue
                preserved, ordered = True, True
                for index, (plain_name, opaque_name) in enumerate(zip(plain[0], transformed[0])):
                    left = [row[index] for row in plain[1:]]
                    right = [row[index] for row in transformed[1:]]
                    if _partition(left) != _partition(right):
                        preserved = False
                    if regime == "monotone-numeric" and columns[plain_name] == "integer":
                        pairs = sorted({(float(a), float(b)) for a, b in zip(left, right) if a and b})
                        if any(a[0] < b[0] and not a[1] < b[1] for a, b in zip(pairs, pairs[1:])):
                            ordered = False
                    if not opaque_name.startswith(side[0] + "_"):
                        errors.append(f"{name}/{kind}/{side}: invalid opaque column id")
                check = {"dataset": name, "scenario_type": kind, "side": side, "regime": regime,
                         "rows": len(plain) - 1, "columns": len(plain[0]),
                         "equality_partition_preserved": preserved,
                         "numeric_order_preserved": ordered if regime == "monotone-numeric" else None}
                checks.append(check)
                if not preserved or (regime == "monotone-numeric" and not ordered):
                    errors.append(f"{name}/{kind}/{side}: structural invariance failed")
    return {"version": VERSION, "input": str(output.resolve()), "checks": checks, "errors": errors,
            "summary": {"checks": len(checks), "passed": len(checks) - len(errors),
                        "failed": len(errors)}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    audit_parser = sub.add_parser("audit")
    audit_parser.add_argument("--raw-root", type=Path, required=True)
    audit_parser.add_argument("--report", type=Path, required=True)
    prepare_parser = sub.add_parser("prepare")
    prepare_parser.add_argument("--raw-root", type=Path, required=True)
    prepare_parser.add_argument("--output", type=Path, required=True)
    prepare_parser.add_argument("--rows", type=int, default=5000)
    prepare_parser.add_argument("--seed", type=int, default=20260904)
    verify_parser = sub.add_parser("verify")
    verify_parser.add_argument("--input", type=Path, required=True)
    verify_parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "verify":
        report = verify(args.input)
        json_write(args.report, report)
        print(json.dumps(report["summary"]))
        return 1 if report["errors"] else 0
    report = audit(args.raw_root)
    if args.command == "audit":
        json_write(args.report, report)
        print(json.dumps(report["summary"]))
        return 0 if report["summary"]["present"] == report["summary"]["required"] else 2
    if report["summary"]["present"] != report["summary"]["required"]:
        parser.error("Required MusicBrainz/Lahman files are missing; run audit")
    if args.rows < 4:
        parser.error("--rows must be at least 4")
    manifest = prepare(args.raw_root, args.output, args.rows, args.seed)
    print(manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
