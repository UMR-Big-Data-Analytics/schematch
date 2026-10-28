"""Publication, download integrity and conversion contracts for standalone preparation."""
import csv
import hashlib
import io
import json
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_datasets as tool
from dataset_support import controlled, external


class DatasetPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.dataset_base = self.root / 'data'
        self.base_patch = patch.object(tool, 'DEFAULT_DATASET_BASE', self.dataset_base)
        self.base_patch.start()

    def tearDown(self):
        self.base_patch.stop()
        self.temporary.cleanup()

    def raw_case(self, bad=False):
        raw = self.root / "input"
        folder = raw / "Valentine-datasets" / "Example" / "case"
        folder.mkdir(parents=True)
        for side in ("source", "target"):
            with (folder / f"case_{side}.csv").open("w", encoding="utf-8", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(['na,me', 'Ünicode'])
                writer.writerow(['001', 'NA'])
                writer.writerow(['line\nbreak', ''])
        (folder / "case_mapping.json").write_text(json.dumps({"matches": [
            {"source_column": "missing" if bad else "na,me", "target_column": "Ünicode"}]}), encoding="utf-8")
        return raw

    def test_lossless_import_config_hashes_and_refusal_to_overwrite(self):
        raw, out = self.raw_case(), self.root / "ready"
        argv = ["prepare", "valentine-layout", "--input", str(raw), "--output", str(out)]
        self.assertEqual(tool.main(argv), 0)
        tables = list(out.glob("*/case/source/*.csv"))
        self.assertEqual(len(tables), 1)
        with tables[0].open(encoding="utf-8", newline="") as stream:
            self.assertEqual(list(csv.reader(stream)), [['na,me', 'Ünicode'], ['001', 'NA'], ['line\nbreak', '']])
        truth = next(out.glob("*/case/ground_truth/*.csv"))
        self.assertEqual(truth.read_text().splitlines(), ['0,1', '0,0'])
        config = (out / "datasets.yaml").read_text()
        configured_path = json.loads(next(line.split(': ', 1)[1] for line in config.splitlines() if line.startswith('path: ')))
        self.assertFalse(Path(configured_path).is_absolute())
        self.assertEqual((self.dataset_base/configured_path).resolve(), tables[0].parents[2])
        self.assertNotIn('.prepare-', config)
        for path in out.rglob('*.json'):
            self.assertNotIn(str(self.root).replace('\\', '\\\\'), path.read_text())
        manifest = json.loads((out / "preparation_manifest.json").read_text())
        self.assertEqual(manifest['scenarios'], 1)
        self.assertEqual(manifest['prepared_files_sha256'][tables[0].relative_to(out).as_posix()],
                         hashlib.sha256(tables[0].read_bytes()).hexdigest())
        before = tables[0].read_bytes()
        self.assertEqual(tool.main(argv), 2)
        self.assertEqual(tables[0].read_bytes(), before)

    def test_invalid_truth_leaves_audit_and_no_published_output(self):
        raw, out = self.raw_case(bad=True), self.root / "ready"
        self.assertEqual(tool.main(['prepare', 'valentine-layout', '--input', str(raw), '--output', str(out)]), 2)
        self.assertFalse(out.exists())
        audit = json.loads((self.root / 'ready-import-report.json').read_text())
        self.assertEqual(audit['summary']['invalid'], 1)
        self.assertFalse(list(self.root.glob('.prepare-*')))

    def test_no_output_or_report_inside_raw(self):
        raw = self.raw_case()
        self.assertEqual(tool.main(['prepare', 'valentine-layout', '--input', str(raw), '--output', str(raw/'ready')]), 2)
        self.assertFalse((raw / 'ready').exists())
        self.assertEqual(tool.main(['prepare', 'valentine-layout', '--input', str(raw),
                                    '--output', str(self.root/'ready'), '--report', str(raw/'audit.json')]), 2)
        self.assertFalse((raw/'audit.json').exists())

    def test_zip_traversal_links_and_duplicate_paths_rejected_before_writes(self):
        for name in ('../escape', '/absolute', 'C:/drive', 'folder\\escape'):
            archive = self.root / 'bad.zip'
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('safe.txt', 'not published')
                package.writestr(name, 'bad')
            if "\\" in name:
                # ZipInfo normalizes Windows backslashes on creation; construct the hostile name verbatim.
                archive.write_bytes(archive.read_bytes().replace(b'folder/escape', b'folder\\escape'))
            destination = self.root / 'extract'
            with self.assertRaises(ValueError):
                tool.extract_zip(archive, destination)
            self.assertFalse((destination/'safe.txt').exists())
        with zipfile.ZipFile(archive, 'w') as package:
            symlink = zipfile.ZipInfo('link')
            symlink.create_system = 3
            symlink.external_attr = 0o120777 << 16
            package.writestr(symlink, '../outside')
        with self.assertRaises(ValueError):
            tool.extract_zip(archive, destination)
        with zipfile.ZipFile(archive, 'w') as package:
            package.writestr('A.txt', 'first')
            package.writestr('a.txt', 'second')
        with self.assertRaises(ValueError):
            tool.extract_zip(archive, destination)

    def test_checksum_cache_and_partial_download_failure(self):
        target = self.root / 'file'
        target.write_bytes(b'expected')
        spec = {'url': 'https://example.org/file', 'checksum': 'sha256:' + hashlib.sha256(b'expected').hexdigest()}
        with patch('urllib.request.urlopen') as network:
            tool.download(spec, target)
            network.assert_not_called()
            target.write_bytes(b'changed')
            with self.assertRaises(ValueError):
                tool.download(spec, target)
            network.assert_not_called()
        new = self.root / 'new'
        response = io.BytesIO(b'wrong data')
        response.geturl = lambda: spec['url']
        with patch('urllib.request.urlopen', return_value=response):
            with self.assertRaises(ValueError):
                tool.download(spec, new)
        self.assertFalse(new.exists())
        self.assertFalse(list(self.root.glob('.download-*')))

    def test_t2d_uses_index_truth_and_retains_split(self):
        base = self.root / 't2d_sm_wh'
        for split in ('train', 'valid', 'test'):
            folder = base / split
            for side in ('webtables', 'dbpedia_tables'):
                (folder / side).mkdir(parents=True)
            stem = split + '_pair'
            for side in ('webtables', 'dbpedia_tables'):
                (folder/side/(stem+'.csv')).write_text('duplicate,duplicate\n001,NA\n', encoding='utf-8')
            with (folder/(split+'_correspondences.csv')).open('w', newline='') as stream:
                writer = csv.writer(stream)
                writer.writerow(['table_name','column_index_left','column_index_right','label'])
                writer.writerow([stem,1,0,1])
        out = self.root/'intermediate'
        manifest = {'scenarios':{}, 'skipped':[], 'notes':[]}
        external.t2dsm(out, manifest, base)
        self.assertEqual(len(manifest['scenarios']), 3)
        mapping = json.loads((out/'train_pair/train_pair_mapping.json').read_text())['matches'][0]
        self.assertEqual(mapping['source_column'], 'duplicate__2')
        self.assertEqual(mapping['target_column'], 'duplicate')
        self.assertEqual(manifest['scenarios']['train_pair']['split'], 'train')

    def test_all_negative_truth_is_explicitly_gated(self):
        raw = self.raw_case()
        mapping = next(raw.rglob('*_mapping.json'))
        mapping.write_text('{"matches":[]}')
        self.assertEqual(tool.lossless.audit(raw)['summary']['invalid'], 1)
        self.assertEqual(tool.lossless.audit(raw, allow_empty_truth=True)['summary']['valid'], 1)

    def test_gdc_source_does_not_implicitly_import_valentine_families(self):
        raw = self.raw_case()
        out = self.root / 'ready'
        self.assertEqual(tool.main(['prepare', 'gdc', '--input', str(raw), '--output', str(out)]), 2)
        self.assertFalse(out.exists())

    def test_exported_configuration_survives_moving_data_and_project_together(self):
        original = self.root / 'original'
        out = original / 'prepared'
        base = original / 'data'
        raw = self.raw_case()
        self.assertEqual(tool.main(['prepare','valentine-layout','--input',str(raw),
                                    '--output',str(out),'--dataset-base',str(base)]), 0)
        moved = self.root / 'moved'
        shutil.copytree(original, moved)
        config = (moved/'prepared/datasets.yaml').read_text()
        path = json.loads(next(line.split(': ',1)[1] for line in config.splitlines() if line.startswith('path: ')))
        self.assertTrue((moved/'data'/path).resolve().is_dir())
        self.assertEqual((moved/'data'/path).resolve().parent, moved/'prepared')

    def test_headerless_postgres_tsv_preserves_quotes_and_null_policy_on_python310(self):
        path = self.root / 'dump'
        path.write_text('1\t"quoted"\t\\N\n', encoding='utf-8')
        self.assertEqual(list(controlled._tsv_rows(path, ['id','name','null'])),
                         [{'id':'1','name':'"quoted"','null':''}])

    def test_standalone_python_needs_no_site_packages_and_marks_profiles_unready(self):
        output = self.root / 'synthetic'
        result = subprocess.run([sys.executable, '-S', str(tool.HERE/'prepare_datasets.py'),
                                 'prepare', 'scale', '--smoke', '--output', str(output),
                                 '--dataset-base',str(self.dataset_base)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        markers = list(output.glob('*/SC*/metadata/profile-status.properties'))
        self.assertEqual(len(markers), 1)
        self.assertIn('source.fd=not_profiled', markers[0].read_text())


if __name__ == '__main__':
    unittest.main()
