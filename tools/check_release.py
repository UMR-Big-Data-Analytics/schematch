"""Check the selected Git files for portable documentation and accidental local artifacts."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess

PROJECT = Path(__file__).resolve().parents[1]
EXCLUDED_TOP = {'data', 'datasets-local', 'results', 'logs', 'target', 'cache', 'rel_exp', '.idea'}


def check(project=PROJECT, working_tree=False):
    command = ['git', 'ls-files', '-z']
    if working_tree:
        command += ['--cached', '--others', '--exclude-standard']
    result = subprocess.run(command, cwd=project, capture_output=True, check=True)
    paths = sorted({Path(name) for name in result.stdout.decode('utf-8').split('\0') if name})
    if working_tree:
        paths = [path for path in paths if (project / path).is_file()]
    problems = []
    for relative in paths:
        path = project / relative
        if not path.is_file():
            problems.append(f'{relative.as_posix()}: selected file missing')
            continue
        if relative.parts[0] in EXCLUDED_TOP or relative.parts[0].startswith(('seed_', '.venv')):
            problems.append(f'{relative.as_posix()}: local artifact selected')
        if '.orig' in relative.name or '__pycache__' in relative.parts or relative.suffix in {'.pyc', '.class', '.log'}:
            problems.append(f'{relative.as_posix()}: generated or backup file selected')
        if path.stat().st_size > 50 * 1024 * 1024:
            problems.append(f'{relative.as_posix()}: exceeds the source-release size budget')
        if path.suffix in {'.md', '.yaml', '.yml'}:
            text = path.read_text(encoding='utf-8-sig')
            if re.search(r'(?<![A-Za-z])[A-Za-z]:[\\/]', text):
                problems.append(f'{relative.as_posix()}: machine-specific path')
            if path.suffix == '.md':
                if re.search(r'\b(?:harness|codex|claude|chatgpt)\b', text, re.I):
                    problems.append(f'{relative.as_posix()}: excluded documentation wording')
                for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)', text):
                    target = target.split('#')[0].strip('<>')
                    if target and '://' not in target and not target.startswith('mailto:') and not (path.parent / target).exists():
                        problems.append(f'{relative.as_posix()}: broken local link {target}')
        if path.suffix in {'.py', '.java', '.yaml', '.yml', '.json', '.md', '.xml'}:
            text = path.read_text(encoding='utf-8-sig')
            if re.search(r'\b(?:ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{30,})\b', text):
                problems.append(f'{relative.as_posix()}: possible credential; inspect locally')
    return {'selected_files': len(paths), 'problems': problems, 'technical_checks_passed': not problems,
            'publication_ready': False, 'pending': ['Upstream and third-party licensing/redistribution review; see docs/THIRD_PARTY.md'],
            'scope': ('Existing tracked and non-ignored untracked files.' if working_tree else 'Current Git selection only;') +
                     ' Historical commits and optional backend availability require separate review.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--working-tree', action='store_true', help='Include non-ignored new files and omit deleted paths before staging a documentation reorganization')
    args = parser.parse_args()
    report = check(working_tree=args.working_tree)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"Checked {report['selected_files']} selected files: {len(report['problems'])} technical issues.")
        for problem in report['problems']:
            print(problem)
        print('Publication still requires the permissions review in docs/THIRD_PARTY.md.')
    return 0 if report['technical_checks_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
