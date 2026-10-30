"""Check repository files for broken documentation links, local paths and accidental artifacts."""
from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import unquote, urlsplit

PROJECT = Path(__file__).resolve().parents[1]
EXCLUDED_TOP = {'data', 'datasets-local', 'results', 'reports', 'logs', 'target', 'cache', 'rel_exp', '.idea'}


def without_fences(text):
    """Exclude fenced examples from rendered links and headings."""
    lines = []
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if fence:
            if re.match(r'^\s{0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*$', line):
                fence = None
            lines.append('')
        elif marker:
            fence = marker.group(1)
            lines.append('')
        else:
            lines.append(line)
    return '\n'.join(lines)


def markdown_anchors(text):
    """GitHub-style heading IDs, including repeated headings and explicit IDs."""
    text = without_fences(text)
    anchors = set(re.findall(r'<[^>]+\b(?:id|name)=[\"\x27]([^\"\x27]+)[\"\x27]', text))
    used = set()
    lines = text.splitlines()
    for index, line in enumerate(lines):
        heading = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        if heading:
            value = heading.group(1)
        elif line.strip() and index + 1 < len(lines) and re.fullmatch(r'\s{0,3}(?:=+|-+)\s*', lines[index + 1]):
            value = line.strip()
        else:
            continue
        value = re.sub(r'!?\[([^\]]+)\]\([^)]*\)', r'\1', value)
        value = html.unescape(re.sub(r'<[^>]+>', '', value)).lower()
        slug = ''.join(c for c in value if c in '-_' or c.isspace() or unicodedata.category(c)[0] in 'LN')
        slug = re.sub(r'\s', '-', slug)
        candidate = slug
        suffix = 0
        while candidate in used:
            suffix += 1
            candidate = f'{slug}-{suffix}'
        used.add(candidate)
        anchors.add(candidate)
    return anchors


def markdown_links(text):
    """Read inline, reference-style and angle-bracket links outside code."""
    text = re.sub(r'(?<!`)(`+)(?!`)[^\n]*?(?<!`)\1(?!`)', '', without_fences(text))
    definitions = {}
    normalize = lambda label: ' '.join(label.split()).casefold()
    for match in re.finditer(r'^\s{0,3}\[([^\]]+)\]:\s*(<[^>]*>|\S+)', text, re.M):
        definitions[normalize(match.group(1))] = match.group(2).strip('<>')
    text = re.sub(r'^\s{0,3}\[[^\]]+\]:.*$', '', text, flags=re.M)
    inline = r'!?\[[^\]\n]*\]\(\s*(<[^>]*>|[^\s()]*(?:\([^()]*\)[^\s()]*)*)(?:\s+[\"\x27][^\n]*?[\"\x27])?\s*\)'
    for match in re.finditer(inline, text):
        yield match.group(1).strip('<>')
    text = re.sub(inline, '', text)
    for match in re.finditer(r'!?\[([^\]\n]+)\](?:\[([^\]\n]*)\])?', text):
        label = normalize(match.group(2) or match.group(1))
        if label in definitions:
            yield definitions[label]
        elif match.group(2) is not None:
            yield f'unresolved-reference:{label}'
    for match in re.finditer(r'<((?:[a-z][a-z0-9+.-]*:)[^<>\s]+)>', text, re.I):
        yield match.group(1)


def check_markdown_links(project, relative, text, selected):
    problems = []
    count = anchors = 0
    root = project.resolve()
    for raw in markdown_links(text):
        target = html.unescape(raw)
        try:
            url = urlsplit(target)
        except ValueError:
            problems.append(f'{relative.as_posix()}: invalid link {target}')
            continue
        if url.scheme in {'http', 'https', 'mailto'}:
            continue
        if url.scheme or url.netloc or target.startswith(('/', '\\')):
            problems.append(f'{relative.as_posix()}: nonportable link {target}')
            continue
        count += 1
        requested = root / relative.parent / unquote(url.path) if url.path else root / relative
        linked = requested.resolve()
        if not linked.is_relative_to(root):
            problems.append(f'{relative.as_posix()}: link leaves the repository {target}')
            continue
        if not linked.exists():
            problems.append(f'{relative.as_posix()}: broken local link {target}')
            continue
        linked_relative = linked.relative_to(root)
        if linked.is_file() and linked_relative not in selected:
            problems.append(f'{relative.as_posix()}: link targets an unpublished local file {target}')
        # Windows resolves case differences that fail on Linux and GitHub.
        current = root
        for part in requested.relative_to(root).parts:
            if part == '..':
                current = current.parent
                continue
            if part not in {entry.name for entry in current.iterdir()}:
                problems.append(f'{relative.as_posix()}: link filename case differs {target}')
                break
            current /= part
        if url.fragment and linked.is_file() and linked.suffix.lower() == '.md':
            anchors += 1
            if unquote(url.fragment) not in markdown_anchors(linked.read_text(encoding='utf-8-sig')):
                problems.append(f'{relative.as_posix()}: broken section anchor {target}')
    return problems, count, anchors


def check(project=PROJECT, working_tree=False):
    project = Path(project)
    command = ['git', 'ls-files', '-z']
    if working_tree:
        command += ['--cached', '--others', '--exclude-standard']
    result = subprocess.run(command, cwd=project, capture_output=True, check=True)
    paths = sorted({Path(name) for name in result.stdout.decode('utf-8').split('\0') if name})
    if working_tree:
        paths = [path for path in paths if (project / path).is_file()]
    problems = []
    local_links = section_anchors = 0
    selected = set(paths)
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
            problems.append(f'{relative.as_posix()}: exceeds the repository file-size budget')
        if path.suffix in {'.md', '.yaml', '.yml'}:
            text = path.read_text(encoding='utf-8-sig')
            if re.search(r'(?<![A-Za-z])[A-Za-z]:[\\/]', text):
                problems.append(f'{relative.as_posix()}: machine-specific path')
            if path.suffix == '.md':
                if re.search(r'\b(?:harness|codex|claude|chatgpt)\b', text, re.I):
                    problems.append(f'{relative.as_posix()}: excluded documentation wording')
                link_problems, links, anchors = check_markdown_links(project, relative, text, selected)
                problems.extend(link_problems)
                local_links += links
                section_anchors += anchors
        if path.suffix in {'.py', '.java', '.yaml', '.yml', '.json', '.md', '.xml'}:
            text = path.read_text(encoding='utf-8-sig')
            if re.search(r'\b(?:ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{30,})\b', text):
                problems.append(f'{relative.as_posix()}: possible credential; inspect locally')
    return {'selected_files': len(paths), 'problems': problems, 'technical_checks_passed': not problems,
            'local_links_checked': local_links, 'section_anchors_checked': section_anchors,
            'scope': ('Existing tracked and non-ignored untracked files.' if working_tree else 'Current Git selection only;') +
                     ' Checks local files only; optional backend availability is checked separately.'}


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
    return 0 if report['technical_checks_passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
