"""Documentation checks should fail for links that break in a public checkout."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_project import check_markdown_links, markdown_anchors, markdown_links


class ProjectLinksTest(unittest.TestCase):
    def check_links(self, text, files):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            files = {'docs/guide.md': text, **files}
            for name, content in files.items():
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding='utf-8')
            return check_markdown_links(project, Path('docs/guide.md'), text, {Path(n) for n in files})

    def test_missing_files_and_renamed_sections_are_reported(self):
        problems, count, anchors = self.check_links(
            '[missing](missing.md) [section](other.md#old-title)', {'docs/other.md': '# New title'})
        self.assertEqual((count, anchors), (2, 1))
        self.assertTrue(any('broken local link' in p for p in problems))
        self.assertTrue(any('broken section anchor' in p for p in problems))

    def test_relative_encoded_paths_references_and_duplicate_headings(self):
        problems, count, anchors = self.check_links(
            '[inline](../My%20File.md#setup-1) [reference][ref] [ref][] [ref]\n\n'
            '[ref]: <../My File.md#setup>', {'My File.md': '# Setup\n## Setup'})
        self.assertEqual(problems, [])
        self.assertEqual((count, anchors), (4, 4))

    def test_internal_app_links_and_paths_outside_repository_are_rejected(self):
        problems, _, _ = self.check_links(
            '[internal](codex://thread/123) [local](file:///tmp/a) '
            '[absolute](/tmp/a) [escape](../../other.md)', {})
        self.assertEqual(len(problems), 4)

    def test_examples_in_code_do_not_create_links_or_anchors(self):
        text = ('# Real\n```markdown\n# Hidden\n[broken](missing.md)\n```\n'
                '~~~markdown\n[broken](other.md)\n~~~\n`[broken](absent.md)`\n[real](#real)')
        self.assertEqual(list(markdown_links(text)), ['#real'])
        self.assertEqual(markdown_anchors(text), {'real'})

    def test_heading_formatting_html_anchors_and_slug_collisions(self):
        self.assertEqual(markdown_anchors(
            '# **Build** `tools` & files\n# Setup\n# Setup-1\n# Setup\n'
            '<a id="custom"></a>\nTitle\n====='),
            {'build-tools--files', 'setup', 'setup-1', 'setup-2', 'custom', 'title'})

    def test_links_with_titles_images_parentheses_and_external_urls(self):
        text = ('[guide](<guide.md> "title") ![figure](plot(1).svg) '
                '<https://example.org/> [mail](mailto:author@example.org)')
        self.assertEqual(list(markdown_links(text)),
                         ['guide.md', 'plot(1).svg', 'mailto:author@example.org', 'https://example.org/'])

    def test_case_mismatch_and_unpublished_local_files_are_reported(self):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            (project / 'Guide.md').write_text('# Guide', encoding='utf-8')
            problems, _, _ = check_markdown_links(
                project, Path('README.md'), '[local](Guide.md)', {Path('README.md')})
            self.assertTrue(any('unpublished local file' in p for p in problems))
            problems, _, _ = check_markdown_links(
                project, Path('README.md'), '[local](guide.md)', {Path('README.md'), Path('Guide.md')})
            self.assertTrue(problems)  # Missing on Linux; case mismatch on Windows.


if __name__ == '__main__':
    unittest.main()
