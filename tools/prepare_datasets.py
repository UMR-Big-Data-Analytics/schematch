"""Download declared releases and prepare datasets from a standalone Schematch checkout.

Python 3.10+, standard library only. See ../docs/datasets.md for sources and recipes.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

from dataset_support import lossless

HERE = Path(__file__).resolve().parent
CATALOG = HERE / "dataset_sources.json"
DEFAULT_WORK = HERE.parent / "datasets-local"
DEFAULT_DATASET_BASE = HERE.parent / "data"
csv.field_size_limit(2**31 - 1)
PREPARATION_MODES = {
    "valentine": "local conversion", "t2dsm": "local conversion", "hdxsm": "local conversion",
    "gdcsm": "local conversion", "madi": "local conversion", "goby": "local conversion",
    "mimic": "local conversion", "synthea": "local conversion",
    "controlled": "generate from local inputs", "scale": "generate", "manual": "manual preparation",
}


def catalog():
    return json.loads(CATALOG.read_text(encoding="utf-8"))["sources"]


def preparation_support(entry):
    adapter = entry["adapter"]
    if adapter not in PREPARATION_MODES:
        raise ValueError(f"Unknown dataset adapter: {adapter}")
    if entry.get("downloads"):
        if adapter in {"manual", "scale", "controlled"}:
            raise ValueError(f"Downloads do not match the preparation mode: {adapter}")
        return "download + prepare"
    return PREPARATION_MODES[adapter]


def relative_path(path: Path, base: Path) -> str:
    try:
        return Path(os.path.relpath(path.resolve(), base.resolve())).as_posix()
    except ValueError as exc:
        raise ValueError("Paths must share a filesystem root to export relative references; "
                         "choose --dataset-base on the output drive and use it as defaultDatasetBasePath") from exc


def portable_provenance(value, roots):
    """Replace runtime roots in exported provenance; labels and source URLs are unchanged."""
    if isinstance(value, dict):
        return {key: portable_provenance(item, roots) for key, item in value.items()}
    if isinstance(value, list):
        return [portable_provenance(item, roots) for item in value]
    if isinstance(value, str):
        for root, replacement in roots:
            for prefix in (str(root), root.as_posix()):
                if value == prefix:
                    return replacement
                if value.startswith(prefix + "/") or value.startswith(prefix + "\\"):
                    return replacement.rstrip("/") + "/" + value[len(prefix)+1:].replace("\\", "/")
    return value


def checked_path(root: Path, name: str) -> Path:
    # Archive paths are POSIX; reject backslashes and Windows drive/ADS syntax too.
    relative = PurePosixPath(name)
    if not name or "\\" in name or ":" in name or relative.is_absolute() or ".." in relative.parts:
        raise ValueError(f"Unsafe relative path: {name!r}")
    target = root.joinpath(*relative.parts).resolve()
    if not target.is_relative_to(root.resolve()) or target == root.resolve():
        raise ValueError(f"Path escapes root: {name!r}")
    return target


def checksum(path: Path, spec: dict) -> str:
    algorithm, expected = spec["checksum"].split(":", 1)
    if algorithm not in {"sha256", "md5"}:
        raise ValueError("Unsupported checksum algorithm")
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    if value.hexdigest() != expected:
        raise ValueError(f"Checksum mismatch for {path}; expected {spec['checksum']}")
    return value.hexdigest()


def download(spec: dict, destination: Path):
    if destination.exists():
        checksum(destination, spec)
        return
    if not spec["url"].startswith("https://"):
        raise ValueError("Download URLs must use HTTPS")
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".download-", dir=destination.parent)
    try:
        request = urllib.request.Request(spec["url"], headers={"User-Agent": "Schematch-dataset-preparation/1"})
        print(f"Downloading {spec['url']}", flush=True)
        with os.fdopen(fd, "wb") as output, urllib.request.urlopen(request, timeout=60) as response:
            if not response.geturl().startswith("https://"):
                raise ValueError("Download redirected outside HTTPS")
            shutil.copyfileobj(response, output)
        checksum(Path(temporary), spec)
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def extract_zip(archive: Path, destination: Path):
    """Extract regular files only, refusing traversal, links and existing targets."""
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        planned, seen = [], set()
        for member in package.infolist():
            target = checked_path(destination, member.orig_filename.rstrip("/"))
            kind = stat.S_IFMT(member.external_attr >> 16)
            if kind not in {0, stat.S_IFREG, stat.S_IFDIR}:
                raise ValueError(f"Unsupported archive link/device: {member.filename}")
            key = str(target).casefold()
            if key in seen:
                raise ValueError(f"Duplicate archive member: {member.filename}")
            seen.add(key)
            if not member.is_dir() and target.exists():
                raise FileExistsError(f"Refusing to overwrite {target}")
            planned.append((member, target))
        for member, target in planned:
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with package.open(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output)


def fetch(source: str, work: Path) -> Path:
    spec = catalog()[source]
    if not spec.get("downloads"):
        raise ValueError(f"{source} requires local inputs. {spec['acquisition']}")
    root = work / "raw" / source
    root.mkdir(parents=True, exist_ok=True)
    for item in spec["downloads"]:
        archive = checked_path(root, item["file"])
        download(item, archive)
        if "extract_to" in item:
            marker = archive.with_name(archive.name + ".extracted.json")
            if marker.exists():
                saved = json.loads(marker.read_text(encoding="utf-8"))
                if saved != {"checksum": item["checksum"], "extract_to": item["extract_to"]}:
                    raise ValueError(f"Extraction settings changed for {archive}; use a fresh --work")
                continue
            target = root if item["extract_to"] == "." else checked_path(root, item["extract_to"])
            extract_zip(archive, target)
            lossless.json_write(marker, {"checksum": item["checksum"], "extract_to": item["extract_to"]})
    return root


def import_raw(raw: Path, output: Path, args, *, domain_catalog=False, allow_empty=False):
    corrections = json.loads(args.corrections.read_text(encoding="utf-8-sig")) if args.corrections else {}
    report = lossless.audit(raw, args.select, corrections, args.trim_header_whitespace,
                            allow_empty_truth=allow_empty)
    if domain_catalog:
        for scenario in report["scenarios"]:
            if "target" in scenario["tables"]:
                scenario["tables"]["target"]["semantics"] = "domain_catalog"
    # Keep the audit even when publication fails. It is outside the raw release.
    lossless.json_write(args.report, portable_provenance(report, args.provenance_roots))
    if report["unmatched_selectors"] or not report["scenarios"]:
        raise ValueError(f"No matching scenarios: {report['unmatched_selectors']}")
    if report["summary"]["invalid"] and not args.skip_invalid:
        raise ValueError(f"{report['summary']['invalid']} invalid scenarios; inspect {args.report}. "
                         "Use explicit corrections or --skip-invalid.")
    for scenario in report["scenarios"]:
        if scenario["status"] == "valid":
            lossless.export_one(raw, output, scenario, args.trim_header_whitespace)
    lossless.json_write(output / "import_audit.json", report)


def dataset_dirs(root: Path):
    result = []
    for path in sorted(root.rglob("source")):
        if path.is_dir() and (path.parent / "target").is_dir():
            result.append(path.parent.parent)
    return sorted(set(result))


def validate_tables(root: Path):
    """Stream every exported CSV row; ensure no adapter silently publishes ragged data."""
    scenarios = 0
    for dataset in dataset_dirs(root):
        for scenario in sorted(dataset.iterdir()):
            if not scenario.is_dir() or not (scenario / "source").is_dir():
                continue
            for side in ("source", "target"):
                files = sorted((scenario / side).glob("*.csv"))
                if not files:
                    raise ValueError(f"No {side} tables: {scenario}")
                for path in files:
                    rows = lossless.csv_rows(path)
                    header = next(rows, [])
                    if not header or len(set(header)) != len(header) or any(not c for c in header):
                        raise ValueError(f"Invalid/duplicate CSV headers: {path}")
                    for number, row in enumerate(rows, 2):
                        if len(row) != len(header):
                            raise ValueError(f"Ragged CSV at {path}:{number}")
            if not (scenario / "ground_truth_full.csv").is_file() and not list((scenario / "ground_truth").glob("*.csv")):
                raise ValueError(f"No evaluation truth: {scenario}")
            scenarios += 1
    if not scenarios:
        raise ValueError("Preparation produced no scenarios")
    return scenarios


def prepare(args):
    source = args.source
    entry = catalog()[source]
    adapter_name = entry["adapter"]
    preparation_support(entry)
    if entry["adapter"] == "manual":
        raise ValueError(f"{source}: {entry['acquisition']} See docs/datasets.md; no automatic adapter is claimed.")
    if args.select is None and source in {"valentine", "gdc"}:
        args.select = [source + "/*"]
    raw = args.input.resolve() if args.input else (args.work / "raw" / source).resolve()
    if args.download:
        if args.input:
            raise ValueError("Use either --input or --download")
        raw = fetch(source, args.work).resolve()
    generated = adapter_name == "scale"
    if not generated and not raw.is_dir():
        raise ValueError(f"Missing {raw}; use fetch/--download or --input")
    output = (args.output or args.work / "prepared" / source).resolve()
    dataset_base = args.dataset_base.resolve()
    # Fail before conversion if a relative configuration cannot represent these roots.
    relative_path(output, dataset_base)
    if not generated:
        relative_path(raw, output)
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite {output}; choose a new --output")
    if not generated and (output.is_relative_to(raw) or raw.is_relative_to(output)):
        raise ValueError("Raw inputs and prepared outputs must be disjoint")
    output.parent.mkdir(parents=True, exist_ok=True)
    if args.report is None:
        args.report = output.with_name(output.name + "-import-report.json")
    args.report = args.report.resolve()
    if not generated and args.report.is_relative_to(raw):
        raise ValueError("Report must be outside raw inputs")
    if args.report == output or args.report.is_relative_to(output):
        raise ValueError("External report must be outside the unpublished output directory")
    if args.report.exists():
        raise FileExistsError(f"Refusing to overwrite report {args.report}")
    stage = Path(tempfile.mkdtemp(prefix=".prepare-", dir=output.parent)).resolve()
    try:
        prepared, intermediate = stage / "prepared", stage / "raw"
        args.provenance_roots = [(prepared,"."), (intermediate,"conversion-input"),
                                 (raw,relative_path(raw,output) if not generated else "generated-input")]
        if adapter_name == "valentine":
            import_raw(raw, prepared, args)
        elif adapter_name in {"t2dsm", "hdxsm", "gdcsm", "madi", "goby"}:
            from dataset_support import external, unified
            base = raw
            if adapter_name == "t2dsm":
                base = raw / "t2d_sm_wh" if (raw / "t2d_sm_wh").is_dir() else raw
            if adapter_name == "hdxsm":
                base = raw / "hdx_datasets_0.2" / "hxd_datasets_0.2"
                if not base.is_dir():
                    base = raw  # also accept the directory containing pair_* directly
            if adapter_name == "madi":
                base = raw / "use cases"
            man = {"corpus": source, "scenarios": {}, "skipped": [], "notes": [], "truth_sha256": {}}
            root = intermediate / "Valentine-datasets" / source.upper()
            root.mkdir(parents=True)
            adapter = getattr(unified if adapter_name in {"madi", "goby"} else external, adapter_name)
            adapter(root, man, base)
            lossless.json_write(intermediate / "conversion_manifest.json", man)
            # Ground truth-free pairs are intentionally retained only in GOBY's frozen design.
            import_raw(intermediate, prepared, args, domain_catalog=adapter_name == "gdcsm", allow_empty=adapter_name == "goby")
            shutil.copyfile(intermediate / "conversion_manifest.json", prepared / "conversion_manifest.json")
        elif adapter_name in {"mimic", "synthea"}:
            from dataset_support import mimic, synthea
            (mimic if adapter_name == "mimic" else synthea).prepare(raw, prepared)
        elif adapter_name == "controlled":
            from dataset_support import controlled
            controlled.prepare(raw, intermediate, args.rows, args.seed)
            report = controlled.verify(intermediate)
            if report["errors"]:
                raise ValueError(f"Controlled transformations failed verification: {report['errors']}")
            import_raw(intermediate, prepared, args)
            shutil.copyfile(intermediate / "controlled-benchmark.json", prepared / "controlled-benchmark.json")
        elif adapter_name == "scale":
            from dataset_support import scale
            cells = [(16, 16, .3, 500)] if args.smoke else scale.cells()
            for n, m, rho, rows in cells:
                for repetition in ((1,) if args.smoke else scale.REPS):
                    scale.one(intermediate, n, m, rho, rows, repetition)
            import_raw(intermediate, prepared, args)
        else:
            raise ValueError(f"No adapter for {source}")
        count = validate_tables(prepared)
        directories = dataset_dirs(prepared)
        for dataset in directories:
            for scenario in dataset.iterdir():
                if scenario.is_dir() and (scenario / "source").is_dir():
                    marker = scenario / "metadata" / "profile-status.properties"
                    if not marker.exists():
                        marker.parent.mkdir(exist_ok=True)
                        marker.write_text("".join(f"{side}.{kind}=not_profiled\n" for side in ("source", "target")
                                                  for kind in ("fd", "ucc", "ind", "dc")), encoding="ascii")
        yaml = "\n".join("---\nname: " + json.dumps(p.name) + "\npath: " +
                         json.dumps(relative_path(output / p.relative_to(prepared), dataset_base)) for p in directories) + "\n"
        (prepared / "datasets.yaml").write_text(yaml, encoding="utf-8")
        hashes = {p.relative_to(prepared).as_posix(): lossless.digest(p)
                  for p in sorted(prepared.rglob("*.csv"))}
        manifest = {"source": source, "upstream": entry, "scenarios": count,
                    "raw_input": None if generated else relative_path(raw, output), "prepared_files_sha256": hashes,
                    "dataset_base_from_prepared_root": relative_path(dataset_base, output),
                    "paths_relative_to": "prepared dataset root; datasets.yaml paths are relative to defaultDatasetBasePath",
                    "adapter_sha256": {p.name: lossless.digest(p) for p in sorted((HERE / "dataset_support").glob("*.py"))},
                    "preparation_script_sha256": lossless.digest(Path(__file__)),
                    "selection": args.select, "skip_invalid": args.skip_invalid,
                    "profile_status": "not_profiled", "python": sys.version,
                    "intermediate_paths": "conversion-input/ identifies temporary converted inputs; their hashes are retained."}
        lossless.json_write(prepared / "preparation_manifest.json", manifest)
        if args.validate_java:
            result = subprocess.run([sys.executable, str(HERE / "audit_datasets.py"), "--dataset", str(prepared),
                                     "--prefix", str(stage / "java-audit")])
            if result.returncode:
                raise ValueError("Java dataset validation failed; see audit output")
            shutil.copyfile(stage / "java-audit-report.json", prepared / "java-audit-report.json")
        roots = [(prepared, "."), (intermediate, "conversion-input"),
                 (raw, relative_path(raw, output) if not generated else "generated-input"),
                 (stage, "preparation-work")]
        for path in prepared.rglob("*.json"):
            lossless.json_write(path, portable_provenance(json.loads(path.read_text(encoding="utf-8")), roots))
        if args.report.is_file():
            lossless.json_write(args.report, portable_provenance(
                json.loads(args.report.read_text(encoding="utf-8")), roots))
        prepared.rename(output)
        print(f"Prepared {count} scenarios -> {output}\nDataset configuration: {output / 'datasets.yaml'}")
    finally:
        # Delete only this invocation's verified private staging directory.
        if stage.parent == output.parent and stage.name.startswith(".prepare-"):
            shutil.rmtree(stage)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("list", help="Show sources and automatic/manual support")
    for command in ("fetch", "prepare"):
        child = commands.add_parser(command)
        child.add_argument("source", choices=sorted(catalog()))
        child.add_argument("--work", type=Path, default=DEFAULT_WORK)
        if command == "prepare":
            child.add_argument("--input", type=Path)
            child.add_argument("--dataset-base", type=Path, default=DEFAULT_DATASET_BASE,
                               help="Directory used by general.yaml defaultDatasetBasePath (default: project data/)")
            child.add_argument("--output", type=Path)
            child.add_argument("--download", action="store_true")
            child.add_argument("--select", action="append", help="Repeatable scenario-ID glob")
            child.add_argument("--corrections", type=Path)
            child.add_argument("--trim-header-whitespace", action="store_true")
            child.add_argument("--skip-invalid", action="store_true")
            child.add_argument("--report", type=Path)
            child.add_argument("--validate-java", action="store_true")
            child.add_argument("--rows", type=int, default=5000, help="Controlled generation only")
            child.add_argument("--seed", type=int, default=20260904, help="Controlled generation only")
            child.add_argument("--smoke", action="store_true", help="One small scaling scenario")
    args = parser.parse_args(argv)
    try:
        if args.command == "list":
            for name, entry in catalog().items():
                print(f"{name:20} {preparation_support(entry):28} {entry['title']}")
        elif args.command == "fetch":
            print(fetch(args.source, args.work.resolve()))
        else:
            if args.rows < 4:
                raise ValueError("--rows must be at least 4")
            if args.select and args.source in {"mimic", "synthea"}:
                raise ValueError("--select is for Valentine-layout imports, not this multi-table adapter")
            prepare(args)
        return 0
    except (ValueError, OSError, csv.Error, KeyError, zipfile.BadZipFile) as exc:
        print(f"Preparation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
