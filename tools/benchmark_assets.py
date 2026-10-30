"""Build, verify and attach table-bound benchmark annotations and dependency profiles.

Python standard library only. No raw table rows are included in asset archives.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

FORMAT = 'schematch-benchmark-assets'
VERSION = 1
HASH = re.compile(r'^[0-9a-f]{64}$')
PROFILE_FILES = {'FD_results.txt', 'UCC_results.txt', 'inds.txt', 'LIMA_DC_results.txt',
                 'source-to-target-inds.txt', 'target-to-source-inds.txt',
                 'profile-status.properties', 'profiling.json', 'multitable_profile.json',
                 'fd.scored.json', 'ucc.scored.json', 'ind.scored.json', 'dc.scored.json'}


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')


def safe_path(root, name):
    p = PurePosixPath(name)
    if not name or '\\' in name or ':' in name or p.is_absolute() or any(x in {'.', '..'} for x in name.split('/')):
        raise ValueError(f'Unsafe relative path: {name!r}')
    path = Path(root).joinpath(*p.parts)
    if not path.resolve().is_relative_to(Path(root).resolve()) or path.is_symlink():
        raise ValueError(f'Path escapes root or uses a link: {name!r}')
    return path


def portable(value):
    """Keep content identifiers instead of private source/cache locations."""
    if isinstance(value, dict):
        return {k: portable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [portable(v) for v in value]
    if isinstance(value, str) and (re.match(r'^[A-Za-z]:[\\/]', value) or value.startswith(('/', '\\\\'))):
        name = value.replace('\\', '/').rstrip('/').split('/')[-1]
        return 'upstream-reference/' + name
    return value


def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8-sig').splitlines()
                if '=' in line and not line.startswith(('#', '!')))


def scenarios(root):
    for source in sorted(root.glob('*/*/source')):
        if source.is_dir() and (source.parent / 'target').is_dir():
            yield source.parent


def payload_files(scenario):
    for p in sorted(scenario.rglob('*')):
        if not p.is_file():
            continue
        relative = p.relative_to(scenario)
        if relative.parts[0] == 'metadata' and p.name in PROFILE_FILES:
            yield p
        elif relative.parts[0] == 'ground_truth' and p.suffix == '.csv':
            yield p
        elif relative.as_posix() == 'ground_truth_full.csv':
            yield p


def build(root, output, identity, version, provenance, dataset_names=None):
    root, output = Path(root).resolve(), Path(output).resolve()
    if output.exists():
        raise FileExistsError(f'Refusing to overwrite {output}')
    dataset_names = dataset_names or {}
    for old, new in dataset_names.items():
        for name in (old, new):
            safe_path(root, name)
            if len(PurePosixPath(name).parts) != 1:
                raise ValueError('Dataset name mappings must contain single directory names')
    records = []
    manifest = {'format': FORMAT, 'format_version': VERSION, 'id': identity,
                'version': version, 'provenance': portable(provenance), 'scenarios': records}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.assets-', dir=output.parent) as temporary:
        archive = Path(temporary) / 'bundle.zip'
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as package:
            for scenario in scenarios(root):
                original_prefix = scenario.relative_to(root).as_posix()
                dataset, scenario_name = original_prefix.split('/')
                prefix = dataset_names.get(dataset, dataset) + '/' + scenario_name
                tables = {p.relative_to(scenario).as_posix(): digest(p)
                          for side in ('source', 'target') for p in sorted((scenario / side).glob('*.csv'))}
                if not tables or not any(n.startswith('source/') for n in tables) or not any(n.startswith('target/') for n in tables):
                    raise ValueError(f'Missing CSV tables in {prefix}')
                files = {}
                original_hashes = {}
                raw_payload = {}
                for p in payload_files(scenario):
                    name = p.relative_to(scenario).as_posix()
                    data = p.read_bytes()
                    original_hashes[name] = hashlib.sha256(data).hexdigest()
                    if p.suffix == '.json':
                        data = json_bytes(portable(json.loads(data)))
                    raw_payload[name] = data
                if not any(n.startswith('ground_truth/') or n == 'ground_truth_full.csv' for n in raw_payload):
                    raise ValueError(f'No ground truth in {prefix}')
                marker_name = 'metadata/profile-status.properties'
                profile_status = {}
                marker = scenario / marker_name
                if marker.is_file():
                    props = properties(marker)
                    for side in ('source', 'target'):
                        candidates = [n for n in tables if n.startswith(side + '/')]
                        expected = props.get(side + '.table.sha256')
                        if expected and (len(candidates) != 1 or tables[candidates[0]] != expected):
                            raise ValueError(f'Stale profile table checksum in {prefix}/{side}')
                        for kind, filename in [('fd', 'FD_results.txt'), ('ucc', 'UCC_results.txt'),
                                               ('ind', 'inds.txt'), ('dc', 'LIMA_DC_results.txt')]:
                            profile_status[side + '.' + kind] = props.get(side + '.' + kind, 'not_profiled')
                            for scored in (False, True):
                                key = side + '.' + kind + ('.scored.sha256' if scored else '.sha256')
                                old_hash = props.get(key)
                                if not old_hash:
                                    continue
                                if kind == 'ind' and not scored:
                                    name = f'metadata/{side}/inds.txt'
                                elif len(candidates) == 1:
                                    table = Path(candidates[0]).stem
                                    name = f'metadata/{side}/{table}/' + (kind + '.scored.json' if scored else filename)
                                else:
                                    raise ValueError(f'Ambiguous profile checksum in {prefix}')
                                original = scenario / name
                                if not original.is_file() or digest(original) != old_hash:
                                    raise ValueError(f'Stale profile checksum in {prefix}/{name}')
                                if name in raw_payload:
                                    props[key] = hashlib.sha256(raw_payload[name]).hexdigest()
                    raw_payload[marker_name] = ''.join(f'{k}={v}\n' for k, v in props.items()).encode('ascii')
                elif (scenario / 'metadata/multitable_profile.json').is_file():
                    profile_status = portable(json.loads((scenario / 'metadata/multitable_profile.json').read_text()))
                for name, data in raw_payload.items():
                    safe_path(scenario, name)
                    member = f'payload/{prefix}/{name}'
                    info = zipfile.ZipInfo(member, date_time=(2020, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    package.writestr(info, data)
                    files[name] = {'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
                records.append({'path': prefix, 'tables': tables, 'files': files,
                                'original_files_sha256': original_hashes,
                                'profile_status': profile_status})
                if prefix != original_prefix:
                    records[-1]['original_scenario_path'] = original_prefix
            if not records:
                raise ValueError('No <dataset>/<scenario>/source and target directories found')
            package.writestr('bundle_manifest.json', json_bytes(manifest))
        verify(archive)
        archive.rename(output)
    return manifest


def validate_manifest(manifest):
    if manifest.get('format') != FORMAT or manifest.get('format_version') != VERSION:
        raise ValueError('Unsupported benchmark asset format')
    if not manifest.get('id') or not manifest.get('version') or not manifest.get('scenarios'):
        raise ValueError('Missing bundle identity, version or scenarios')
    expected = {'bundle_manifest.json'}
    expected_folded = {'bundle_manifest.json'}
    scenarios_seen = set()
    for scenario in manifest['scenarios']:
        prefix = scenario['path']
        safe_path(Path.cwd(), prefix)
        if len(PurePosixPath(prefix).parts) != 2 or prefix.casefold() in scenarios_seen:
            raise ValueError(f'Invalid or duplicate scenario: {prefix}')
        scenarios_seen.add(prefix.casefold())
        for name, checksum in scenario['tables'].items():
            safe_path(Path.cwd(), name)
            if len(PurePosixPath(name).parts) != 2 or name.split('/')[0] not in {'source', 'target'} or not name.endswith('.csv') or not HASH.fullmatch(checksum):
                raise ValueError(f'Invalid required table: {name}')
        if not all(any(n.startswith(side + '/') for n in scenario['tables']) for side in ('source', 'target')):
            raise ValueError('Each scenario requires source and target tables')
        if not any(n == 'ground_truth_full.csv' or n.startswith('ground_truth/') for n in scenario['files']):
            raise ValueError('Each scenario requires ground truth')
        for name, spec in scenario['files'].items():
            safe_path(Path.cwd(), name)
            p = PurePosixPath(name)
            if not (name == 'ground_truth_full.csv' or (p.parts[0] == 'ground_truth' and p.suffix == '.csv') or (p.parts[0] == 'metadata' and p.name in PROFILE_FILES)):
                raise ValueError(f'Not an annotation or profile: {name}')
            if not HASH.fullmatch(spec['sha256']) or not isinstance(spec['bytes'], int) or spec['bytes'] < 0:
                raise ValueError(f'Invalid asset checksum or size: {name}')
            member = f'payload/{prefix}/{name}'
            if member.casefold() in expected_folded:
                raise ValueError(f'Duplicate asset target: {member}')
            expected.add(member)
            expected_folded.add(member.casefold())
    return expected


def verify(archive, expected_sha256=None):
    archive = Path(archive)
    if expected_sha256 and (not HASH.fullmatch(expected_sha256) or digest(archive) != expected_sha256):
        raise ValueError('Archive SHA-256 mismatch')
    with zipfile.ZipFile(archive) as package:
        members = package.infolist()
        names = [m.filename for m in members]
        if len(names) != len(set(n.casefold() for n in names)):
            raise ValueError('Duplicate archive members')
        for member in members:
            safe_path(Path.cwd(), member.filename)
            mode = stat.S_IFMT(member.external_attr >> 16)
            if mode not in {0, stat.S_IFREG} or member.is_dir():
                raise ValueError(f'Unsupported archive member: {member.filename}')
        manifest = json.loads(package.read('bundle_manifest.json'))
        expected = validate_manifest(manifest)
        if set(names) != expected:
            raise ValueError('Archive contains missing or undeclared files')
        for scenario in manifest['scenarios']:
            for name, spec in scenario['files'].items():
                member = f"payload/{scenario['path']}/{name}"
                info = package.getinfo(member)
                if info.file_size != spec['bytes']:
                    raise ValueError(f'Asset size mismatch: {member}')
                value = hashlib.sha256()
                with package.open(member) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                        value.update(chunk)
                if value.hexdigest() != spec['sha256']:
                    raise ValueError(f'Asset SHA-256 mismatch: {member}')
    return manifest


def install(archive, prepared, *, expected_sha256=None, dry_run=False):
    manifest = verify(archive, expected_sha256)
    prepared = Path(prepared).resolve()
    if not prepared.is_dir():
        raise ValueError('Prepared dataset root must already exist')
    planned = []
    for scenario in manifest['scenarios']:
        folder = safe_path(prepared, scenario['path'])
        actual = {p.relative_to(folder).as_posix() for side in ('source', 'target')
                  for p in (folder / side).glob('*.csv')}
        if actual != set(scenario['tables']):
            raise ValueError(f"Table selection differs: {scenario['path']}")
        for name, expected in scenario['tables'].items():
            table = safe_path(folder, name)
            if not table.is_file() or digest(table) != expected:
                raise ValueError(f"Prepared table SHA-256 mismatch: {scenario['path']}/{name}")
        for name, spec in scenario['files'].items():
            target = safe_path(folder, name)
            if target.exists():
                if target.is_file() and digest(target) == spec['sha256']:
                    continue
                if name != 'metadata/profile-status.properties' or not target.is_file():
                    raise ValueError(f'Refusing to replace a conflicting annotation/profile: {target}')
                old = properties(target)
                if not old or any(value != 'not_profiled' for key, value in old.items() if key.endswith(('.fd', '.ucc', '.ind', '.dc'))) or any('sha256' in key for key in old):
                    raise ValueError(f'Refusing to replace an existing profile status: {target}')
            planned.append((f"payload/{scenario['path']}/{name}", target))
    if not dry_run:
        # All archive, table and conflict checks finish before writing any file.
        with zipfile.ZipFile(archive) as package:
            for member, target in planned:
                target.parent.mkdir(parents=True, exist_ok=True)
                with package.open(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)
    return {'id': manifest['id'], 'version': manifest['version'], 'scenarios': len(manifest['scenarios']),
            'files_to_attach': len(planned), 'dry_run': dry_run}


def read_catalog(location):
    if location.startswith('https://'):
        with urllib.request.urlopen(location, timeout=30) as response:
            if not response.url.startswith('https://'):
                raise ValueError('Catalog redirected outside HTTPS')
            catalog = json.load(response)
    else:
        catalog = json.loads(Path(location).read_text(encoding='utf-8-sig'))
    if catalog.get('format') != 'schematch-benchmark-catalog' or catalog.get('format_version') != VERSION:
        raise ValueError('Unsupported benchmark catalog')
    return catalog


@contextmanager
def catalog_archive(location, identity):
    catalog = read_catalog(location)
    entry = next((e for e in catalog['bundles'] if e['id'] == identity), None)
    if entry is None:
        raise ValueError(f'Unknown bundle: {identity}')
    spec = entry['archive']
    if not HASH.fullmatch(spec['sha256']):
        raise ValueError('Catalog archive requires a SHA-256 checksum')
    local = None if location.startswith('https://') else safe_path(Path(location).resolve().parent, spec['path'])
    if local and local.is_file():
        yield local, spec['sha256'], entry
        return
    url = spec.get('url')
    if not url or not url.startswith('https://'):
        raise ValueError('Archive is not available locally and has no published HTTPS URL')
    with tempfile.TemporaryDirectory(prefix='schematch-assets-') as temporary:
        archive = Path(temporary) / 'bundle.zip'
        with urllib.request.urlopen(url, timeout=60) as response, archive.open('wb') as output:
            if not response.url.startswith('https://'):
                raise ValueError('Archive redirected outside HTTPS')
            shutil.copyfileobj(response, output)
        yield archive, spec['sha256'], entry


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    listing = commands.add_parser('list')
    listing.add_argument('--catalog', required=True)
    for command in ('verify', 'install'):
        child = commands.add_parser(command)
        selection = child.add_mutually_exclusive_group(required=True)
        selection.add_argument('--archive', type=Path)
        selection.add_argument('--catalog')
        child.add_argument('--id')
        child.add_argument('--sha256')
        if command == 'install':
            child.add_argument('--prepared', type=Path, required=True)
            child.add_argument('--dry-run', action='store_true')
    building = commands.add_parser('build')
    building.add_argument('--input', type=Path, required=True)
    building.add_argument('--output', type=Path, required=True)
    building.add_argument('--id', required=True)
    building.add_argument('--version', required=True)
    building.add_argument('--provenance', type=Path, required=True)
    building.add_argument('--dataset-name', action='append', default=[], metavar='OLD=NEW',
                          help='Explicitly map a legacy dataset directory to its current preparation name')
    args = parser.parse_args(argv)
    try:
        if args.command == 'list':
            for entry in read_catalog(args.catalog)['bundles']:
                print(f"{entry['id']:36} {entry['scenario_count']:5} scenarios  {entry['title']}")
        elif args.command == 'build':
            names = {}
            for mapping in args.dataset_name:
                if mapping.count('=') != 1:
                    raise ValueError('--dataset-name requires OLD=NEW')
                old, new = mapping.split('=')
                if old in names:
                    raise ValueError('Duplicate dataset name mapping')
                names[old] = new
            manifest = build(args.input, args.output, args.id, args.version,
                             json.loads(args.provenance.read_text(encoding='utf-8-sig')), names)
            print(json.dumps({'id': manifest['id'], 'scenarios': len(manifest['scenarios']),
                              'sha256': digest(args.output)}, indent=2))
        else:
            def run(archive, checksum):
                if args.command == 'install':
                    result = install(archive, args.prepared, expected_sha256=checksum, dry_run=args.dry_run)
                else:
                    manifest = verify(archive, checksum)
                    result = {'id': manifest['id'], 'version': manifest['version'],
                              'scenarios': len(manifest['scenarios']), 'verified': True}
                return result
            if args.catalog:
                if not args.id:
                    raise ValueError('--catalog requires --id')
                with catalog_archive(args.catalog, args.id) as (archive, checksum, entry):
                    identity = verify(archive, checksum)
                    if identity['id'] != entry['id'] or identity['version'] != entry['version']:
                        raise ValueError('Catalog and archive identities differ')
                    result = run(archive, checksum)
            else:
                result = run(args.archive, args.sha256)
            print(json.dumps(result, indent=2))
        return 0
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as exc:
        print(f'Benchmark assets: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
