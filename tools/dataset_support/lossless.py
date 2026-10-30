"""Lossless benchmark adapters and a generated Schematch compatibility view.

The raw release is never modified. Ground truth is evaluation-only; the profiler
consumes exported table files, not mappings. Use ``python tools/prepare_datasets.py --help``.
"""
from __future__ import annotations

import argparse
import csv
import fnmatch
import hashlib
import json
import os
import re
import shutil
import tempfile
from collections import Counter
from pathlib import Path

VERSION = 1
SIDES = ("source", "target")


def digest(path: Path) -> str:
    # hashlib.file_digest is Python 3.11+, while the working matcher environment is also used on 3.10.
    hash_value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hash_value.update(block)
    return hash_value.hexdigest()


def json_write(path: Path, value: object) -> None:
    """Atomic metadata publication; callers own and validate the destination."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".publish-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def inside(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f"Path escapes dataset root: {relative}")
    return path


def safe_name(value: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]", "_", value)[:100]
    if cleaned != value or not cleaned:
        cleaned += "-" + hashlib.sha256(value.encode()).hexdigest()[:10]
    return cleaned


def csv_rows(path: Path):
    # No type inference: IDs, leading zeros, literal 'NA', etc. stay strings.
    with path.open(encoding="utf-8-sig", newline="") as stream:
        yield from csv.reader(stream, strict=True)


def columns(path: Path, trim: bool = False) -> tuple[list[str], list[str]]:
    rows = csv_rows(path)
    try:
        original = next(rows)
    except StopIteration:
        raise ValueError(f"Empty CSV: {path}") from None
    finally:
        rows.close()
    labels = [s.strip() for s in original] if trim else original[:]
    if any(not x for x in labels):
        raise ValueError("Empty column name")
    if len(labels) != len(set(labels)):
        raise ValueError("Duplicate column names (possibly caused by trimming)")
    return original, labels


def discover(root: Path) -> list[dict]:
    records = []
    valentine = root / "Valentine-datasets"
    if valentine.is_dir():
        # Include incomplete scenario directories even when mapping.json is absent.
        directories = sorted({p.parent for p in valentine.rglob("*")
                              if p.is_file() and p.name.endswith(
                                  ("_mapping.json", "_source.csv", "_target.csv",
                                   "_source.json", "_target.json"))})
        for directory in directories:
            mappings = sorted(directory.glob("*_mapping.json"))
            stems = {p.name.removesuffix("_mapping.json") for p in mappings}
            if not stems:
                stems = {p.name.rsplit("_", 1)[0] for p in directory.iterdir()
                         if p.name.endswith(("_source.csv", "_target.csv", "_source.json", "_target.json"))}
            relative = directory.relative_to(valentine)
            family = relative.parts[0]
            for stem in sorted(stems):
                subtype = next((x for x in relative.parts if x in (
                    "Unionable", "View-Unionable", "Joinable", "Semantically-Joinable")), None)
                if subtype is None:
                    name = directory.name.lower()
                    subtype = ("Semantically-Joinable" if "semjoin" in name else
                               "View-Unionable" if "viewunion" in name else
                               "Joinable" if "joinable" in name else "Unionable")
                records.append({"id": "valentine/" + relative.as_posix() + "/" + stem,
                                "family": family, "scenario_type": subtype,
                                "dataset": safe_name(family + "-" + subtype),
                                "scenario": safe_name(stem), "format": "valentine",
                                "mapping": str((directory / (stem + "_mapping.json")).relative_to(root)),
                                **({"design": str((directory / (stem + "_design.json")).relative_to(root))}
                                   if (directory / (stem + "_design.json")).is_file() else {}),
                                **{side: str((directory / (stem + f"_{side}.csv")).relative_to(root))
                                   for side in SIDES}})
    gdc = root / "gdc"
    if gdc.is_dir():
        names = {p.stem for p in (gdc / "ground-truth").glob("*.csv")}
        for name in sorted(names):
            records.append({"id": "gdc/" + name, "family": "GDC", "scenario_type": "schema-to-domain",
                            "dataset": "GDC", "scenario": safe_name(name), "format": "gdc",
                            "mapping": str(Path("gdc/ground-truth") / (name + ".csv")),
                            "source": str(Path("gdc/source-tables") / (name + ".csv")),
                            "target": "gdc/target-tables/gdc_unique_columns_concat_values.csv"})
    return records


def audit(root: Path, selectors: list[str] | None = None, corrections: dict | None = None,
          trim: bool = False, allow_empty_truth: bool = False) -> dict:
    root = root.resolve()
    corrections = corrections or {}
    records = []
    used_destinations = set()
    for candidate in discover(root):
        if selectors and not any(fnmatch.fnmatchcase(candidate["id"], p) for p in selectors):
            continue
        row = dict(candidate, errors=[], corrections=[], tables={})
        override = corrections.get(candidate["id"], {})
        for side in SIDES:
            if side in override.get("files", {}):
                row[side] = override["files"][side]
                row["corrections"].append({"kind": "file", "side": side,
                                           "from": candidate[side], "to": row[side]})
            try:
                path = inside(root, row[side])
                original, labels = columns(path, trim)
                row["tables"][side] = {"raw_path": row[side], "original_labels": original,
                                       "labels": labels,
                                       "semantics": "domain_catalog" if candidate["format"] == "gdc" and side == "target"
                                       else "observations"}
                for old, new in zip(original, labels):
                    if old != new:
                        row["corrections"].append({"kind": "header_trim", "side": side,
                                                   "from": old, "to": new})
            except (OSError, ValueError, csv.Error) as exc:
                row["errors"].append(f"{side}: {exc}")
        try:
            mapping_path = inside(root, row["mapping"])
            if row["format"] == "valentine":
                mappings = json.loads(mapping_path.read_text(encoding="utf-8-sig"))["matches"]
                pairs = [(x["source_column"], x["target_column"]) for x in mappings]
            else:
                with mapping_path.open(encoding="utf-8-sig", newline="") as stream:
                    pairs = [(x["original_paper_variable_names"], x["GDC_format_variable_names"])
                             for x in csv.DictReader(stream)]
            resolved = []
            for pair in pairs:
                indices = []
                for side, label in zip(SIDES, pair):
                    replacement = override.get("columns", {}).get(side, {}).get(label, label)
                    if replacement != label:
                        row["corrections"].append({"kind": "mapping_alias", "side": side,
                                                   "from": label, "to": replacement})
                    labels = row["tables"].get(side, {}).get("labels", [])
                    if replacement not in labels:
                        row["errors"].append(f"Unresolved {side} mapping column: {label!r}")
                    else:
                        indices.append(labels.index(replacement))
                if len(indices) == 2:
                    resolved.append(indices)
            unique = sorted({tuple(x) for x in resolved})
            row["ground_truth"] = [list(x) for x in unique]
            row["n_mapping_rows"] = len(pairs)
            row["n_duplicate_pairs"] = len(resolved) - len(unique)
            row["one_to_many_sources"] = sum(v > 1 for v in Counter(x[0] for x in unique).values())
            row["many_to_one_targets"] = sum(v > 1 for v in Counter(x[1] for x in unique).values())
            if not pairs and not allow_empty_truth:
                row["errors"].append("No ground-truth correspondences")
        except (OSError, ValueError, KeyError, TypeError, csv.Error) as exc:
            row["errors"].append(f"mapping: {exc}")
        if "design" in row:
            try:
                design_path = inside(root, row["design"])
                design = json.loads(design_path.read_text(encoding="utf-8-sig"))
                if not isinstance(design, dict) or design.get("scenario_id") != row["id"]:
                    raise ValueError("design scenario_id does not match discovered scenario")
                row["design_spec"] = design
            except (OSError, ValueError, TypeError, json.JSONDecodeError) as exc:
                row["errors"].append(f"design: {exc}")
        destination = (row["dataset"], row["scenario"])
        if destination in used_destinations:
            row["errors"].append("Export destination collision")
        used_destinations.add(destination)
        row["errors"] = sorted(set(row["errors"]))
        row["status"] = "invalid" if row["errors"] else "valid"
        records.append(row)
    unmatched = [p for p in selectors or [] if not any(fnmatch.fnmatchcase(r["id"], p) for r in records)]
    return {"version": VERSION, "raw_root": str(root), "audit_depth": "headers_and_mappings",
            "unmatched_selectors": unmatched,
            "trim_header_whitespace": trim, "scenarios": records,
            "summary": {"total": len(records), "valid": sum(x["status"] == "valid" for x in records),
                        "invalid": sum(x["status"] != "valid" for x in records)}}


def export_one(root: Path, destination: Path, scenario: dict, trim: bool) -> Path:
    """Validate every row and publish an entirely new scenario atomically."""
    if scenario["status"] != "valid":
        raise ValueError(f"Invalid scenario: {scenario['id']}")
    if destination.resolve().is_relative_to(root.resolve()) or root.resolve().is_relative_to(destination.resolve()):
        raise ValueError("Export and raw roots must be disjoint")
    final = destination / scenario["dataset"] / scenario["scenario"]
    if final.exists():
        raise FileExistsError(f"Refusing to overwrite {final}")
    final.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".import-", dir=final.parent))
    try:
        manifest = {"version": VERSION, "id": scenario["id"], "family": scenario["family"],
                    "scenario_type": scenario["scenario_type"], "corrections": scenario["corrections"],
                    "csv_policy": {"encoding": "utf-8", "delimiter": ",", "quotechar": '"',
                                   "escapechar": None, "null_token": "", "type_inference": False,
                                   "trim_header_whitespace": trim}, "tables": {},
                    "ground_truth": {"path": "ground_truth/source___target.csv",
                                     "pairs": scenario["ground_truth"],
                                     "one_to_many_sources": scenario["one_to_many_sources"],
                                     "many_to_one_targets": scenario["many_to_one_targets"],
                                     "raw_path": str(inside(root, scenario["mapping"])),
                                     "raw_sha256": digest(inside(root, scenario["mapping"]))}}
        if "design" in scenario:
            raw_design = inside(root, scenario["design"])
            shutil.copyfile(raw_design, stage / "design.json")
            manifest["design"] = {"path": "design.json", "sha256": digest(stage / "design.json"),
                                  "raw_path": str(raw_design), "raw_sha256": digest(raw_design),
                                  "spec": scenario["design_spec"]}
        for side in SIDES:
            info = scenario["tables"][side]
            raw = inside(root, info["raw_path"])
            before = digest(raw)
            out = stage / side / f"{side}.csv"
            out.parent.mkdir()
            rows = csv_rows(raw)
            original = next(rows)
            if original != info["original_labels"]:
                raise ValueError("CSV header changed since audit")
            count = 0
            with out.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.writer(stream, lineterminator="\n")
                writer.writerow(info["labels"])
                for count, row in enumerate(rows, 1):
                    if len(row) != len(original):
                        raise ValueError(f"Ragged CSV: {raw}, data row {count}, {len(row)} != {len(original)}")
                    writer.writerow(row)
            if digest(raw) != before:
                raise ValueError(f"Input changed while importing {raw}")
            manifest["tables"][side] = {"path": f"{side}/{side}.csv", "sha256": digest(out),
                                        "raw_path": str(raw), "raw_sha256": before,
                                        "semantics": info["semantics"], "rows": count,
                                        "columns": [{"id": f"c{i:04d}", "index": i, "label": label,
                                                     "original_label": original[i]}
                                                    for i, label in enumerate(info["labels"])]}
        gt = stage / manifest["ground_truth"]["path"]
        gt.parent.mkdir()
        pairs = {tuple(x) for x in scenario["ground_truth"]}
        with gt.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            for i in range(len(scenario["tables"]["source"]["labels"])):
                writer.writerow([int((i, j) in pairs) for j in range(len(scenario["tables"]["target"]["labels"]))])
        manifest["ground_truth"]["sha256"] = digest(gt)
        json_write(stage / "manifest.json", manifest)
        # Explicit state protects new exports from implicit PRISMA profiling.
        metadata = stage / "metadata"
        metadata.mkdir()
        (metadata / "profile-status.properties").write_text(
            "\n".join(f"{side}.{kind}=not_profiled" for side in SIDES
                      for kind in ("fd", "ucc", "ind", "dc")) + "\n",
            encoding="ascii")
        stage.rename(final)
    finally:
        if stage.exists():
            shutil.rmtree(stage)  # only this function's private staging directory
    return final / "manifest.json"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("audit", "import"):
        cmd = sub.add_parser(name)
        cmd.add_argument("--raw-root", type=Path, required=True)
        cmd.add_argument("--select", action="append", help="Scenario ID glob; repeatable")
        cmd.add_argument("--corrections", type=Path, help="Explicit scenario file/column aliases")
        cmd.add_argument("--trim-header-whitespace", action="store_true")
        cmd.add_argument("--report", type=Path, required=True)
        if name == "import":
            cmd.add_argument("--output", type=Path, required=True)
            cmd.add_argument("--skip-invalid", action="store_true")
    args = parser.parse_args(argv)
    corrections = json.loads(args.corrections.read_text(encoding="utf-8")) if args.corrections else {}
    report = audit(args.raw_root, args.select, corrections, args.trim_header_whitespace)
    if args.report.resolve().is_relative_to(args.raw_root.resolve()):
        parser.error("Audit report must be outside the raw release")
    json_write(args.report, report)
    print(json.dumps(report["summary"]), flush=True)
    if report["unmatched_selectors"]:
        print("No scenario matched: " + ", ".join(report["unmatched_selectors"]))
        return 2
    if not report["scenarios"]:
        return 2
    if args.command == "audit":
        return 2 if report["summary"]["invalid"] else 0
    if report["summary"]["invalid"] and not args.skip_invalid:
        print("Import refused: invalid scenarios; inspect the audit or explicitly use --skip-invalid")
        return 2
    failed = 0
    for record in report["scenarios"]:
        if record["status"] != "valid":
            continue
        try:
            print(export_one(args.raw_root, args.output, record, args.trim_header_whitespace), flush=True)
            record["import_status"] = "complete"
        except (OSError, ValueError, csv.Error) as exc:
            failed += 1
            record["import_status"] = "failed"
            record["import_error"] = str(exc)
            print(f"IMPORT FAILED {record['id']}: {exc}", flush=True)
    json_write(args.report, report)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
