"""Audit bundled matcher configurations and Python method registries. No external source tree is required."""
from __future__ import annotations

import argparse
import ast
from collections import Counter
import json
import re
from pathlib import Path


def registry(path):
    for node in ast.parse(path.read_text(encoding="utf-8-sig")).body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "METHODS" for t in node.targets):
            return {ast.literal_eval(key) for key in node.value.keys}
    raise ValueError(f"No METHODS registry: {path}")


def discover_matchers(source, project):
    """Follow superclass chains so helpers are excluded and inherited scope is accurate."""
    declarations = {}
    for file in sorted(source.rglob("*.java")):
        text = file.read_text(encoding="utf-8-sig")
        declaration = re.search(r"public\s+((?:(?:abstract|final)\s+)*)class\s+(\w+)(?:\s+extends\s+(\w+))?", text)
        if declaration:
            modifiers, name, parent = declaration.groups()
            if name in declarations:
                raise ValueError(f"Ambiguous class name in matcher inventory: {name}")
            declarations[name] = (parent, "abstract" in modifiers, file, text)

    def ancestors(name):
        chain = []
        while name in declarations:
            if name in chain:
                raise ValueError(f"Cyclic superclass chain: {name}")
            chain.append(name)
            name = declarations[name][0]
        return chain

    entries, wrappers = [], {}
    for name, (parent, abstract, file, text) in declarations.items():
        chain = ancestors(name)
        if abstract or "Matcher" not in chain:
            continue
        package = file.parent.relative_to(source).as_posix().replace("/", ".")
        scope = "table pair" if "TablePairMatcher" in chain else "scenario"
        if "NamedPythonMatcher" in chain:
            backend = "Python seed server"
        elif "NamedScenarioPythonMatcher" in chain:
            backend = "Python study server"
        elif name in {"IVMatcher", "IVPlusValueMatcher", "C3Matcher"}:
            backend = "Java composition + Python seeds"
        elif name == "PythonServerMatcher":
            backend = "Generic Python seed bridge"
        elif name == "ScenarioPythonServerMatcher":
            backend = "Generic Python study bridge"
        elif name == "EmbdiMatcher":
            backend = "External HTTP service"
        else:
            backend = "Java"
        purpose = "matcher"
        if name in {"ConstantMatcher", "OracleMatcher", "RandomMatcher", "RandomEnsembleMatcher", "EmbeddingRandomMatcher"}:
            purpose = "oracle control (uses ground truth)" if name == "OracleMatcher" else "control"
        elif name in {"PythonServerMatcher", "ScenarioPythonServerMatcher"}:
            purpose = "generic transport"
        elif name == "FileSeedMatcher":
            purpose = "file input adapter"
        entry = {"name": name, "package": package, "backend": backend, "scope": scope,
                 "purpose": purpose, "source": file.relative_to(project).as_posix()}
        if parent in {"NamedPythonMatcher", "NamedScenarioPythonMatcher"}:
            method = re.search(r'super\("([^"\n]+)"\)', text)
            if not method:
                raise ValueError(f"Missing fixed server method: {name}")
            entry["method"] = method.group(1)
            wrappers.setdefault(backend, set()).add(entry["method"])
        entries.append(entry)
    return entries, wrappers


def publish(path, content, check):
    if check:
        if not path.is_file() or path.read_text(encoding="utf-8") != content:
            raise ValueError(f"Stale inventory: {path.name}; rerun its audit command without --check")
    else:
        path.write_text(content, encoding="utf-8")


def main():
    project = Path(__file__).resolve().parents[1]
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--seeds", type=Path, default=project/"tools/matcher_seeds.json")
    ap.add_argument("--check", action="store_true", help="Check generated inventories without changing files")
    args = ap.parse_args()
    source = project / "src/main/java/de/uni_marburg/schematch/matching"
    resources = project / "src/main/resources"
    entries, wrappers = discover_matchers(source, project)
    keys = {(x["name"], x["package"]) for x in entries}
    for backend, script in (("Python seed server", "seed_server.py"), ("Python study server", "study_server.py")):
        served = registry(resources / "python_matchers" / script)
        covered = wrappers.get(backend, set())
        if served != covered:
            raise ValueError(f"{script}: missing named classes {served-covered}; unsupported adapters {covered-served}")

    snapshot=json.loads(args.seeds.read_text(encoding="utf-8"))
    parameter_cases=snapshot["parameter_cases"]
    for case in parameter_cases:
        if (case["name"],case["package"]) not in keys:
            raise ValueError(f"Missing matcher class: {case['package']}.{case['name']}")
    # Resource paths in an audit snapshot are illustrative. Class/configuration tests do not open them.
    for case in parameter_cases:
        for key, value in list(case["params"].items()):
            if key in {"thesaurusFile", "icFile"} and isinstance(value, str) and ":/" in value:
                case["params"][key] = "<configured resource>"
            if key == "options" and isinstance(value, str):
                case["params"][key] = re.sub(r"[A-Za-z]:/[^\"]+", "<configured resource>", value)

    deferred=snapshot["file_seeds"]
    backend_counts = dict(sorted(Counter(entry["backend"] for entry in entries).items()))
    catalog={"description":"Concrete class inventory, including presets, controls and transports; not an algorithm or installation count",
             "backend_counts": backend_counts,
             "matchers":entries,"study_seed_count":snapshot["study_seed_count"],
             "parameter_cases":parameter_cases,"file_seeds":deferred}
    publish(resources / "matcher_catalog.json", json.dumps(catalog, indent=2) + "\n", args.check)
    scenario = [e for e in entries if e["backend"] == "Python study server"]
    scenario_note = f"Scenario adapters provide {len(scenario)} presets for {len({e['method'] for e in scenario})} registered methods."
    lines = ["# Schematch matcher inventory", "",
             "Generated by `python tools/audit_matchers.py`. See [the matcher guide](../matchers.md) for usage and limitations.", "",
             f"Audit: **{len(entries)} concrete classes**, **{len(parameter_cases)} configuration-loading cases**; all configured classes exist.",
             "These are classes, including configurable variants, presets, controls and generic transports; this is not a count of distinct algorithms.",
             "Configuration cases check parameter variants and adapters; they are not additional algorithms or bundled benchmark results.",
             "Every method registered by either Python server has a named adapter. Environment/model availability is separate.", "",
             "| Implementation | Classes |", "|---|---|"]
    def display_backend(backend):
        return backend.replace("Python study", "Python scenario").replace("Python seeds", "Python table-pair scores")
    lines += [f"| {display_backend(backend)} | {count} |" for backend, count in backend_counts.items()]
    lines += ["", "EmbDI requires a separately installed HTTP service. COMA and Cupid require installed Valentine; SBERT and other model methods have additional requirements.",
              "See [Python installation](../python-matchers.md). " + scenario_note,
              "OracleMatcher reads ground truth and is only a control. Java ensembles/refinements may require configured components or dependency profiles.", "",
              "| Class | Package | Implementation | Scope | Purpose | Server method |", "|---|---|---|---|---|---|"]
    for entry in entries:
        lines.append(f"| [{entry['name']}](../../{entry['source']}) | `{entry['package']}` | {display_backend(entry['backend'])} | {entry['scope']} | {entry['purpose']} | `{entry.get('method','')}` |")
    lines += ["", "## Archived score-matrix identifiers", "",
              "These entries are **not** live API adapters, and their matrices are not bundled. Supply externally computed matrices through `FileSeedMatcher` or Similarity Flooding's FILE input.",
              "Identifiers retain their original names for compatibility with archived files. `{regime}` denotes a user-chosen dataset variant, such as readable or recoded values.", "",
              "| Archived identifier | Suggested local matrix directory |", "|---|---|"]
    lines += [f"| `{row['seed']}` | `seed_in/{row['seed']}/{{regime}}` |" for row in deferred]
    publish(project / "docs/reference/matcher-catalog.md", "\n".join(lines) + "\n", args.check)
    print(f"Audited {len(entries)} concrete matcher classes and {len(parameter_cases)} configuration-loading cases. "
          "Both Python registries have complete named-adapter coverage.")


if __name__ == "__main__":
    main()
