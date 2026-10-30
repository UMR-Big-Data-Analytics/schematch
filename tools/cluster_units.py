"""Derive bootstrap resampling units from scenarios that share a table.

Scenarios built from the same table are not independent observations. This
links every scenario to the SHA-256 of each of its tables, role-agnostically (a
table used as a source in one scenario and as a target in another is the same
node), and takes connected components. The result is a grouping manifest for
`evaluate_paper.py --groups`: one `unit` per component, `family` per dataset.

Hashes come from each scenario's import manifest (`tables.<side>.raw_sha256`,
the bytes of the original release file) when present, else from the prepared
CSV files. Standard library only. See ../EVALUATION_AND_DATASETS.md.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
from pathlib import Path
import sys

SIDES = ("source", "target")


def components(edges: dict[str, list[str]]) -> dict[str, str]:
    """Union-find over scenario and table nodes; returns scenario -> component root."""
    parent: dict[str, str] = {}

    def find(x):
        parent.setdefault(x, x)
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x
    for scenario, tables in edges.items():
        for t in tables:
            ra, rb = find("T:" + t), find("S:" + scenario)
            if ra != rb:
                parent[ra] = rb
    return {scenario: find("S:" + scenario) for scenario in edges}


def file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def table_hashes(scenario: Path, sides=SIDES, source: str = "auto") -> list[str]:
    """Hashes of a scenario's tables on the chosen sides; `source` is auto, manifest or files."""
    manifest = scenario / "manifest.json"
    if source in ("auto", "manifest") and manifest.is_file():
        tables = json.loads(manifest.read_text(encoding="utf-8")).get("tables", {})
        hashes = [tables.get(side, {}).get("raw_sha256") for side in sides]
        if all(hashes):
            return hashes
    if source == "manifest":
        raise ValueError(f"{scenario}: manifest.json has no raw_sha256 for {sides}")
    return [file_sha256(p) for side in sides for p in sorted((scenario / side).glob("*.csv"))]


def units(root: Path, select=None, sides=SIDES, source: str = "auto") -> tuple[dict, dict]:
    """(grouping manifest, scenario -> hashes) for every `<dataset>/<scenario>` below a corpus root."""
    edges, family = {}, {}
    for dataset in sorted(p for p in root.iterdir() if p.is_dir()):
        for scenario in sorted(p for p in dataset.iterdir() if p.is_dir()):
            key = f"{dataset.name}/{scenario.name}"
            if not (scenario / "source").is_dir() or (select and not any(fnmatch.fnmatchcase(key, s) for s in select)):
                continue
            edges[key] = table_hashes(scenario, sides, source)
            family[key] = dataset.name
    if not edges:
        raise ValueError(f"No scenarios below {root}")
    comp = components(edges)
    # Name each unit after its first member, so names are stable under unrelated additions.
    first = {}
    for key in sorted(comp):
        first.setdefault(comp[key], key)
    return {key: {"family": family[key], "unit": "unit:" + first[comp[key]]} for key in sorted(comp)}, edges


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True, help="Corpus root containing <dataset>/<scenario>/")
    parser.add_argument("--out", type=Path, required=True, help="Grouping manifest JSON to write")
    parser.add_argument("--select", action="append", help="Repeatable <dataset>/<scenario> glob")
    parser.add_argument("--sides", choices=("both", "source", "target"), default="both",
                        help="Link through these sides only, e.g. 'source' when every scenario shares one target")
    parser.add_argument("--hashes", choices=("auto", "manifest", "files"), default="auto")
    parser.add_argument("--family", help="Use one family label for all scenarios instead of the dataset name")
    args = parser.parse_args(argv)
    try:
        if args.out.exists():
            raise FileExistsError(f"Refusing to overwrite {args.out}")
        sides = SIDES if args.sides == "both" else (args.sides,)
        groups, _ = units(args.input, args.select, sides, args.hashes)
        if args.family:
            for item in groups.values():
                item["family"] = args.family
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(groups, indent=1), encoding="utf-8")
        sizes = {}
        for item in groups.values():
            sizes[item["unit"]] = sizes.get(item["unit"], 0) + 1
        print(f"{len(groups)} scenarios in {len(sizes)} units (largest {max(sizes.values())}, "
              f"{sum(1 for n in sizes.values() if n == 1)} singletons) -> {args.out}")
        return 0
    except (ValueError, OSError, json.JSONDecodeError) as exc:
        print(f"Cluster derivation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
