"""Write recoded, relabelled or permuted copies of prepared Schematch datasets.

Python 3.10+, standard library only. Input is a corpus root containing
`<dataset>/<scenario>/` directories, such as the output of prepare_datasets.py.
See ../DATASETS.md ("Transformed variants") for the recipes and their caveats.
"""
from __future__ import annotations

import argparse
import csv
import fnmatch
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

from dataset_support import transforms

HERE = Path(__file__).resolve().parent


def selected(root: Path, patterns):
    cases = transforms.scenarios(root)
    if patterns:
        cases = [(d, s) for d, s in cases if any(fnmatch.fnmatchcase(f"{d.name}/{s.name}", p) for p in patterns)]
    if not cases:
        raise ValueError(f"No scenarios selected below {root}")
    return cases


def run(args, stage: Path) -> dict:
    root = args.input
    record = {}
    if args.command == "md5":
        cohorts = json.loads(args.cohorts.read_text(encoding="utf-8")) if args.cohorts else {}
        problems = []
        for dataset, scenario in selected(root, args.select):
            key = f"{dataset.name}/{scenario.name}"
            rec, issues = transforms.md5_scenario(scenario, stage / dataset.name / scenario.name, dataset.name,
                                                  args.seed, cohorts.get(key, "unknown"))
            record[key] = rec
            problems += [f"{key} {issue}" for issue in issues]
        if problems:
            raise ValueError("Recoding changed null/distinct counts: " + "; ".join(problems[:10]))
    elif args.command == "opaque-labels":
        datasets = sorted({d for d, _ in selected(root, args.select)})
        for dataset in datasets:
            record[dataset.name] = transforms.opaque_labels_dataset(dataset, stage / dataset.name,
                                                                    args.fraction, args.seed, args.limit)
    elif args.command == "opaque-tables":
        for dataset, scenario in selected(root, args.select):
            record[f"{dataset.name}/{scenario.name}"] = transforms.opaque_tables_scenario(
                scenario, stage / dataset.name / scenario.name, dataset.name, args.variant, args.seed)
    elif args.command == "permute":
        for dataset, scenario in selected(root, args.select):
            record[f"{dataset.name}/{scenario.name}"] = transforms.permute_scenario(
                scenario, stage / dataset.name / scenario.name, dataset.name, args.key)
    elif args.command == "knobs":
        knobs = args.knob or transforms.knob_names()
        for dataset, scenario in selected(root, args.select):
            key = args.key.format(dataset=dataset.name, scenario=scenario.name)
            record[f"{dataset.name}/{scenario.name}"] = {
                knob: transforms.knob_scenario(scenario, stage / dataset.name / f"{scenario.name}__{knob}", knob, key)
                for knob in knobs}
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    help_text = {
        "md5": "Per-side salted MD5 of labels and values; nulls kept empty (single-table)",
        "opaque-labels": "Unsalted label tokens, values unchanged; FD/UCC profiles emptied",
        "opaque-tables": "Salted table and column names ('on'), optionally also MD5 values ('md5'); multi-table",
        "permute": "Seeded column permutation per side and table; truth and JSON specs follow",
        "knobs": "Controlled target-side knobs base/K1/K2p*/K3p*/K4f* (single-table)",
    }
    for name, text in help_text.items():
        child = commands.add_parser(name, help=text)
        child.add_argument("--input", type=Path, required=True, help="Corpus root containing <dataset>/<scenario>/")
        child.add_argument("--output", type=Path, required=True, help="New directory; never overwritten")
        child.add_argument("--select", action="append", help="Repeatable <dataset>/<scenario> glob")
        if name in ("md5", "opaque-tables"):
            child.add_argument("--seed", default=transforms.DEFAULT_MD5_SEED, help="Master salt seed")
        if name == "md5":
            child.add_argument("--cohorts", type=Path, help="Optional JSON {dataset/scenario: cohort label}")
        if name == "opaque-labels":
            child.add_argument("--fraction", type=float, default=1.0, help="Share of labels to obscure")
            child.add_argument("--seed", type=int, default=0)
            child.add_argument("--limit", type=int, help="Only the first N scenarios of each dataset")
        if name == "opaque-tables":
            child.add_argument("--variant", choices=("on", "md5"), required=True)
        if name == "permute":
            child.add_argument("--key", default=transforms.DEFAULT_PERMUTATION_KEY,
                               help="Permutation key template; fields {dataset} {scenario} {side} {table}")
        if name == "knobs":
            child.add_argument("--knob", action="append", choices=transforms.knob_names())
            child.add_argument("--key", default="x79|{dataset}|{scenario}",
                               help="Case key template; fields {dataset} {scenario}")
    args = parser.parse_args(argv)
    try:
        args.input, output = args.input.resolve(), args.output.resolve()
        if not args.input.is_dir():
            raise ValueError(f"Missing input {args.input}")
        if output.exists():
            raise FileExistsError(f"Refusing to overwrite {output}; choose a new --output")
        if output.is_relative_to(args.input) or args.input.is_relative_to(output):
            raise ValueError("Input and output must be disjoint")
        output.parent.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix=".transform-", dir=output.parent))
        try:
            record = run(args, stage)
            settings = {k: (v.name if isinstance(v, Path) else v) for k, v in vars(args).items()
                        if k not in ("input", "output", "command")}
            manifest = {"transformation": args.command, "settings": settings, "scenarios": record,
                        "script_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in (Path(__file__), HERE / "dataset_support" / "transforms.py")}}
            (stage / "transformation_manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
            stage.rename(output)
        finally:
            if stage.exists() and stage.name.startswith(".transform-"):
                shutil.rmtree(stage)
        print(f"Wrote {len(record)} {'datasets' if args.command == 'opaque-labels' else 'scenarios'} -> {output}")
        return 0
    except (ValueError, OSError, csv.Error, KeyError, json.JSONDecodeError) as exc:
        print(f"Transformation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
