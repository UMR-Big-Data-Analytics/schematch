"""Asset attachment must reject incompatible tables and conflicting annotations."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark_assets as tool


class BenchmarkAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.prepared = self.root / 'prepared'
        self.scenario = self.prepared / 'Example/case'
        for side in ('source', 'target'):
            table = self.scenario / side / f'{side}.csv'
            table.parent.mkdir(parents=True)
            table.write_bytes(b'name,value\nalice,1\n')
            profile = self.scenario / f'metadata/{side}/{side}/FD_results.txt'
            profile.parent.mkdir(parents=True)
            profile.write_bytes(b'[0]->1\n')
        (self.scenario / 'ground_truth_full.csv').write_bytes(b';name;value\nname;1;0\nvalue;0;1\n')
        self.marker = self.scenario / 'metadata/profile-status.properties'
        self.marker.write_text(''.join(
            f'{side}.fd=complete\n{side}.table.sha256={tool.digest(self.scenario/side/(side+".csv"))}\n'
            f'{side}.fd.sha256={tool.digest(self.scenario/f"metadata/{side}/{side}/FD_results.txt")}\n'
            for side in ('source', 'target')))
        self.archive = self.root / 'assets.zip'

    def build(self):
        return tool.build(self.prepared, self.archive, 'example', '1', {'source': 'synthetic'})

    def empty_target(self):
        target = self.root / 'target'
        shutil.copytree(self.prepared, target)
        shutil.rmtree(target / 'Example/case/metadata')
        (target / 'Example/case/ground_truth_full.csv').unlink()
        return target

    def test_archive_excludes_raw_rows_and_preserves_profiles_and_truth(self):
        self.build()
        with zipfile.ZipFile(self.archive) as package:
            self.assertFalse(any('/source/source.csv' in name for name in package.namelist()))
        target = self.empty_target()
        planned = tool.install(self.archive, target, dry_run=True)
        self.assertGreater(planned['files_to_attach'], 0)
        self.assertFalse((target / 'Example/case/metadata').exists())
        tool.install(self.archive, target)
        self.assertEqual(tool.install(self.archive, target)['files_to_attach'], 0)
        self.assertEqual((target/'Example/case/ground_truth_full.csv').read_bytes(),
                         (self.scenario/'ground_truth_full.csv').read_bytes())

    def test_changed_table_rejected_before_writes(self):
        self.build()
        target = self.empty_target()
        (target/'Example/case/target/target.csv').write_bytes(b'changed\n')
        with self.assertRaisesRegex(ValueError, 'table SHA-256 mismatch'):
            tool.install(self.archive, target)
        self.assertFalse((target/'Example/case/metadata').exists())

    def test_conflicting_truth_rejected_before_writes(self):
        self.build()
        target = self.empty_target()
        (target/'Example/case/ground_truth_full.csv').write_bytes(b'conflict')
        with self.assertRaisesRegex(ValueError, 'conflicting annotation'):
            tool.install(self.archive, target)
        self.assertFalse((target/'Example/case/metadata').exists())

    def test_fresh_preparation_marker_can_be_replaced_but_completed_profiles_cannot(self):
        self.build()
        target = self.empty_target()
        marker = target/'Example/case/metadata/profile-status.properties'
        marker.parent.mkdir(parents=True)
        marker.write_text('source.fd=not_profiled\ntarget.fd=not_profiled\n')
        tool.install(self.archive, target)
        self.assertIn('source.fd=complete', marker.read_text())
        marker.write_text('source.fd=failed\n')
        with self.assertRaisesRegex(ValueError, 'existing profile status'):
            tool.install(self.archive, target)

    def test_changed_archive_missing_or_undeclared_files_and_unsafe_paths_are_rejected(self):
        self.build()
        with self.assertRaisesRegex(ValueError, 'Archive SHA-256 mismatch'):
            tool.verify(self.archive, '0'*64)
        with zipfile.ZipFile(self.archive, 'a') as package:
            package.writestr('payload/Example/case/source/stolen.csv', 'raw rows')
        with self.assertRaisesRegex(ValueError, 'undeclared files'):
            tool.verify(self.archive)
        for path in ('../escape', '/absolute', 'C:/drive', 'metadata\\file', 'metadata/../file'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                tool.safe_path(self.root, path)

    def test_stale_profiles_are_not_packaged(self):
        (self.scenario/'source/source.csv').write_bytes(b'changed\n')
        with self.assertRaisesRegex(ValueError, 'Stale profile table checksum'):
            self.build()
        self.assertFalse(self.archive.exists())

    def test_portable_scored_provenance_updates_marker_hash(self):
        scored = self.scenario/'metadata/source/source/fd.scored.json'
        scored.write_text(json.dumps({'tool_identity': 'F:/private/cache/identity.json'}))
        with self.marker.open('a') as stream:
            stream.write('source.fd.scored.sha256='+tool.digest(scored)+'\n')
        manifest = self.build()
        spec = manifest['scenarios'][0]['files']['metadata/source/source/fd.scored.json']
        with zipfile.ZipFile(self.archive) as package:
            data = package.read('payload/Example/case/metadata/source/source/fd.scored.json')
            marker = package.read('payload/Example/case/metadata/profile-status.properties')
            self.assertNotIn(b'F:/', data)
            self.assertIn(spec['sha256'].encode(), marker)

    def test_catalog_selection_checks_archive_identity_and_integrity(self):
        self.build()
        catalog = self.root/'catalog.json'
        catalog.write_text(json.dumps({'format': 'schematch-benchmark-catalog', 'format_version': 1,
            'bundles': [{'id': 'example', 'version': '1', 'archive': {'path': 'assets.zip',
                'sha256': tool.digest(self.archive), 'url': None}}]}))
        with tool.catalog_archive(str(catalog), 'example') as (path, checksum, entry):
            self.assertEqual(tool.verify(path, checksum)['id'], entry['id'])
        with self.assertRaisesRegex(ValueError, 'Unknown bundle'):
            with tool.catalog_archive(str(catalog), 'missing'):
                pass

    def test_explicit_dataset_rename_preserves_table_bindings(self):
        manifest = tool.build(self.prepared, self.archive, 'example', '1', {}, {'Example': 'Renamed'})
        scenario = manifest['scenarios'][0]
        self.assertEqual(scenario['path'], 'Renamed/case')
        self.assertEqual(scenario['original_scenario_path'], 'Example/case')
        target = self.empty_target()
        (target/'Example').rename(target/'Renamed')
        self.assertGreater(tool.install(self.archive, target)['files_to_attach'], 0)

    def test_alternative_local_truth_is_not_silently_ignored(self):
        self.build()
        target = self.empty_target()
        alternative = target/'Example/case/ground_truth/other.csv'
        alternative.parent.mkdir()
        alternative.write_bytes(b'other truth')
        with self.assertRaisesRegex(ValueError, 'absent from the bundle'):
            tool.install(self.archive, target)
        self.assertFalse((target/'Example/case/metadata').exists())

    def test_custom_status_marker_is_not_overwritten(self):
        self.build()
        target = self.empty_target()
        marker = target/'Example/case/metadata/profile-status.properties'
        marker.parent.mkdir()
        marker.write_text('custom.status=retained\n')
        with self.assertRaisesRegex(ValueError, 'existing profile status'):
            tool.install(self.archive, target)


if __name__ == '__main__':
    unittest.main()
