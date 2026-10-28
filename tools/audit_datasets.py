"""Discover conventional dataset folders and audit all CSV rows/GT through the normal Java parsers.

Use --dataset for dataset directories or --manifest for an explicit list of local inputs.
The command never modifies inputs, mines profiles, or downloads dependencies.
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def discover(root):
    directories = sorted(p for p in root.iterdir() if p.is_dir())
    if any((p/"source").is_dir() and (p/"target").is_dir() for p in directories):
        return [root]
    return [p for p in directories if any((s/"source").is_dir() and (s/"target").is_dir()
                                         for s in p.iterdir() if s.is_dir())]


def main():
    project = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, action="append", default=[])
    parser.add_argument("--manifest", type=Path, help='JSON list of {"path": "relative/dataset"}, relative to the manifest')
    parser.add_argument("--retry-discovery", type=Path, help="Retry roots from an earlier discovery-errors JSON")
    parser.add_argument("--exclude-manifest", type=Path)
    parser.add_argument("--prefix", type=Path, default=project/"target/dataset-audit")
    args = parser.parse_args()
    roots = [(p.resolve(), str(p)) for p in args.dataset]
    if args.manifest:
        for item in json.loads(args.manifest.read_text(encoding="utf-8-sig")):
            value = item["path"] if isinstance(item,dict) else item
            roots.append(((args.manifest.resolve().parent/Path(value)).resolve(),value))
    if args.retry_discovery:
        roots.extend((Path(item["root"]),item["root"]) for item in json.loads(args.retry_discovery.read_text(encoding="utf-8-sig")))
    excluded = {str(Path(item["path"]).resolve()) for item in json.loads(args.exclude_manifest.read_text(encoding="utf-8-sig"))} if args.exclude_manifest else set()
    if not roots:
        parser.error("Supply --dataset, --manifest, or --retry-discovery")
    entries, errors, seen = [], [], set()
    for root, origin in roots:
        try:
            datasets = discover(root)
            if not datasets:
                raise ValueError("No conventional dataset directories found")
            for dataset in datasets:
                key = str(dataset.resolve())
                if key not in excluded and key not in seen:
                    seen.add(key)
                    entries.append({"name":dataset.name,"path":key,"origin":origin})
        except (OSError, ValueError) as exc:
            errors.append({"root":str(root),"error":str(exc)})
    prefix = args.prefix.resolve()
    prefix.parent.mkdir(parents=True,exist_ok=True)
    manifest = prefix.with_name(prefix.name+"-manifest.json")
    report = prefix.with_name(prefix.name+"-report.json")
    manifest.write_text(json.dumps(entries,indent=2)+"\n",encoding="utf-8")
    prefix.with_name(prefix.name+"-discovery-errors.json").write_text(json.dumps(errors,indent=2)+"\n",encoding="utf-8")
    print(f"Discovered {len(entries)} datasets; {len(errors)} discovery failures",flush=True)
    if not entries:
        raise SystemExit(2 if errors else 0)
    cp_file = project/"target/cp.txt"
    if not cp_file.is_file():
        raise SystemExit("Build Schematch and create target/cp.txt using Maven dependency:build-classpath first")
    import os
    cp = str(project/"target/classes")+os.pathsep+cp_file.read_text(encoding="utf-8-sig").strip()
    result = subprocess.run(["java","-cp",cp,"de.uni_marburg.schematch.utils.DatasetAudit",str(manifest),str(report)],cwd=project)
    raise SystemExit(result.returncode or (2 if errors else 0))


if __name__ == "__main__":
    main()
