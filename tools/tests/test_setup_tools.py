"""Prerequisite failures and platform-independent launch behavior."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import check_setup
import run_schematch


class SetupToolsTest(unittest.TestCase):
    def test_java_versions_and_unsupported_installation(self):
        for text, expected in [('java version "1.8.0_412"',8),('openjdk version "17.0.12"',17),
                               ('javac 21.0.1',21),('invalid',None)]:
            self.assertEqual(check_setup.java_major(text),expected)
        with patch('check_setup.shutil.which',return_value=None):
            self.assertEqual(check_setup.command_check('java',17)['status'],'missing')

    def test_missing_local_library_is_an_actionable_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            project=Path(temporary)
            (project/'pom.xml').write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies><dependency><systemPath>${project.basedir}/src/lib/example.jar</systemPath>
              </dependency></dependencies></project>''')
            with patch('check_setup.command_check',return_value={'name':'command','status':'ok','detail':'present'}):
                report=check_setup.check(project)
            self.assertFalse(report['ready'])
            library=next(row for row in report['checks'] if row['name']=='src/lib/example.jar')
            self.assertEqual(library['status'],'missing')

    def test_launcher_uses_project_root_and_platform_separator_and_returns_process_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            project=Path(temporary)
            compiled=project/'target/classes/de/uni_marburg/schematch/Main.class'
            compiled.parent.mkdir(parents=True)
            compiled.write_bytes(b'fixture')
            (project/'target/cp.txt').write_text('dependency.jar')
            with patch.object(run_schematch,'PROJECT',project),patch('run_schematch.subprocess.call',return_value=7) as call:
                self.assertEqual(run_schematch.main(['--jvm-arg=-Xmx4g']),7)
            args=call.call_args[0][0]
            self.assertEqual(args[1],'-Xmx4g')
            self.assertEqual(args[3],str(project/'target/classes')+os.pathsep+'dependency.jar')
            self.assertEqual(call.call_args.kwargs['cwd'],project)

    def test_launcher_does_not_start_an_unbuilt_project(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(run_schematch,'PROJECT',Path(temporary)),patch('run_schematch.subprocess.call') as call:
                self.assertEqual(run_schematch.main([]),1)
                call.assert_not_called()


if __name__=='__main__':
    unittest.main()
