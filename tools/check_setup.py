"""Check the local Schematch build and optional evaluation prerequisites without changing files."""
from __future__ import annotations

import argparse
import importlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]


def java_major(version):
    match = re.search(r'(?:version\s+)?["\s]?(\d+)(?:\.(\d+))?', version)
    if not match:
        return None
    return int(match[2]) if match[1] == '1' and match[2] else int(match[1])


def command_check(command, minimum=None):
    executable = shutil.which(command)
    if not executable:
        return {"name": command, "status": "missing", "detail": f"Add {command} to PATH."}
    try:
        result = subprocess.run([executable, '-version' if command in {'java','javac'} else '--version'],
                                capture_output=True, text=True, timeout=15)
        output = (result.stdout + result.stderr).strip().splitlines()
        version = output[0] if output else "No version returned"
        major = java_major(version) if minimum else None
        ok = result.returncode == 0 and (minimum is None or major is not None and major >= minimum)
        return {"name": command, "status": "ok" if ok else "error", "detail": version,
                **({"required_major": minimum} if minimum else {})}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"name": command, "status": "error", "detail": type(exc).__name__}


def check(project=PROJECT, paper_evaluation=False):
    checks = [{"name":"Python", "status":"ok" if sys.version_info >= (3,10) else "error",
               "detail":sys.version.split()[0], "required":"3.10+"}]
    checks.extend(command_check(command, minimum) for command,minimum in [('java',17),('javac',17),('mvn',None)])
    namespace = {'m':'http://maven.apache.org/POM/4.0.0'}
    try:
        pom = ET.parse(project/'pom.xml')
        for element in pom.findall('.//m:dependency/m:systemPath', namespace):
            relative = element.text.replace('${project.basedir}/','')
            present = not Path(relative).is_absolute() and (project/relative).is_file()
            checks.append({"name":relative,"status":"ok" if present else "missing",
                           "detail":"Local library present" if present else "Restore this required library before building."})
    except (OSError, ET.ParseError) as exc:
        checks.append({"name":"pom.xml","status":"error","detail":type(exc).__name__})
    for filename in ('general.yaml','datasets.yaml','first_line_matchers.yaml','second_line_matchers.yaml','metrics.yaml'):
        present = (project/'src/main/resources'/filename).is_file()
        checks.append({"name":filename,"status":"ok" if present else "missing",
                       "detail":"Configuration present" if present else "Restore the configuration file."})
    if paper_evaluation:
        for name in ('numpy','scipy'):
            try:
                package = importlib.import_module(name)
                checks.append({"name":name,"status":"ok","detail":package.__version__})
            except (ImportError,OSError) as exc:
                checks.append({"name":name,"status":"missing","detail":
                    f"{type(exc).__name__}: install requirements.txt in the selected environment."})
    return {"checks":checks,"ready":all(item['status']=='ok' for item in checks),
            "scope":"Local prerequisites only; data, models and remote matcher availability require their own checks."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paper-evaluation',action='store_true',help='Also import NumPy and SciPy')
    parser.add_argument('--json',action='store_true',help='Print a machine-readable report')
    args = parser.parse_args(argv)
    report = check(paper_evaluation=args.paper_evaluation)
    if args.json:
        print(json.dumps(report,indent=2))
    else:
        for item in report['checks']:
            print(f"{item['status'].upper():7} {item['name']}: {item['detail']}")
        print("Local prerequisites passed." if report['ready'] else "Resolve the missing/error entries before continuing.")
        print(report['scope'])
    return 0 if report['ready'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
