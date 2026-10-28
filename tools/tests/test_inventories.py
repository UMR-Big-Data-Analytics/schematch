"""Keep published capabilities aligned with executable classes and preparation recipes."""
import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import audit_dataset_sources
import audit_matchers
import prepare_datasets


class InventoriesTest(unittest.TestCase):
    def test_helper_classes_excluded_and_indirect_matchers_inherit_scope(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            declarations = {
                "Matcher": "public abstract class Matcher {}",
                "TablePairMatcher": "public abstract class TablePairMatcher extends Matcher {}",
                "PresetBase": "public abstract class PresetBase extends TablePairMatcher {}",
                "Preset": "public final class Preset extends PresetBase {}",
                "Helper": "public class Helper extends SomeLibraryClass {}",
                "GraphHelper": "public class GraphHelper {}",
                "ScenarioPreset": "public class ScenarioPreset extends Matcher {}",
            }
            for name, source in declarations.items():
                (root / (name + '.java')).write_text(source, encoding='utf-8')
            entries, _ = audit_matchers.discover_matchers(root, root)
            self.assertEqual({row['name']: row['scope'] for row in entries},
                             {'Preset': 'table pair', 'ScenarioPreset': 'scenario'})

    def test_real_catalog_distinguishes_services_and_ground_truth_controls(self):
        project = TOOLS.parent
        entries, wrappers = audit_matchers.discover_matchers(
            project / 'src/main/java/de/uni_marburg/schematch/matching', project)
        by_name = {row['name']: row for row in entries}
        self.assertEqual(by_name['EmbdiMatcher']['backend'], 'External HTTP service')
        self.assertEqual(by_name['EmbdiMatcher']['scope'], 'scenario')
        self.assertIn('ground truth', by_name['OracleMatcher']['purpose'])
        self.assertEqual(by_name['PythonServerMatcher']['purpose'], 'generic transport')
        for backend, script in [('Python seed server', 'seed_server.py'), ('Python study server', 'study_server.py')]:
            self.assertEqual(wrappers[backend], audit_matchers.registry(project / 'src/main/resources/python_matchers' / script))

    def test_dataset_audit_rejects_unimplemented_adapters_and_unverifiable_downloads(self):
        sources = prepare_datasets.catalog()
        audit_dataset_sources.inventory(sources)
        invalid = copy.deepcopy(sources)
        invalid['t2dsm']['adapter'] = 'unimplemented'
        with self.assertRaisesRegex(ValueError, 'Unknown dataset adapter'):
            audit_dataset_sources.inventory(invalid)
        invalid = copy.deepcopy(sources)
        del invalid['t2dsm']['downloads'][0]['checksum']
        with self.assertRaisesRegex(ValueError, 'checksum'):
            audit_dataset_sources.inventory(invalid)
        invalid = copy.deepcopy(sources)
        invalid['t2dsm']['downloads'][0]['file'] = '../outside.zip'
        with self.assertRaisesRegex(ValueError, 'Unsafe relative path'):
            audit_dataset_sources.inventory(invalid)

    def test_generated_inventories_are_current(self):
        for script in ('audit_matchers.py', 'audit_dataset_sources.py'):
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, str(TOOLS / script), '--check'],
                                        cwd=TOOLS.parent, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
