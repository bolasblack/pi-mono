#!/usr/bin/env python3
"""
TDD tests for simple_yaml.py YAML frontmatter functions.

Test based on function signatures:
- parse_frontmatter(content: str) -> tuple[dict, str]  # Returns (frontmatter, body)
- serialize_frontmatter(frontmatter: dict, body: str) -> str
"""

import unittest
import sys
from pathlib import Path

# Add parent directory to path to import simple_yaml
sys.path.insert(0, str(Path(__file__).parent))
import simple_yaml


class TestParseFrontmatter(unittest.TestCase):
    """Test parse_frontmatter() - extract YAML frontmatter dict and body."""

    def test_parse_basic_frontmatter(self):
        """Should parse simple frontmatter fields and extract body."""
        content = """---
title: "Test Decision"
description: "A test description"
tags: skills/agent-centric
---

## Context

Body content here.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertEqual(fm["title"], "Test Decision")
        self.assertEqual(fm["description"], "A test description")
        self.assertEqual(fm["tags"], "skills/agent-centric")
        self.assertIn("## Context", body)
        self.assertIn("Body content here.", body)

    def test_parse_preserves_string_type(self):
        """Should keep string fields as strings."""
        content = """---
title: "Test"
tags: global, skills/test
---

Body.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertIsInstance(fm["title"], str)
        self.assertIsInstance(fm["tags"], str)
        self.assertEqual(fm["tags"], "global, skills/test")

    def test_parse_preserves_integer_type(self):
        """Should keep integer fields as integers."""
        content = """---
title: "Test"
count: 42
version: 2
---

Body.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertIsInstance(fm["count"], int)
        self.assertEqual(fm["count"], 42)
        self.assertIsInstance(fm["version"], int)
        self.assertEqual(fm["version"], 2)

    def test_parse_preserves_boolean_type(self):
        """Should keep boolean fields as booleans."""
        content = """---
title: "Test"
enabled: true
deprecated: false
---

Body.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertIsInstance(fm["enabled"], bool)
        self.assertIs(fm["enabled"], True)
        self.assertIsInstance(fm["deprecated"], bool)
        self.assertIs(fm["deprecated"], False)

    def test_parse_empty_frontmatter(self):
        """Should handle empty frontmatter block."""
        content = """---
---

Body content.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertEqual(fm, {})
        self.assertIn("Body content.", body)

    def test_parse_no_frontmatter(self):
        """Should return empty dict for files without frontmatter."""
        content = """# Regular Markdown

No frontmatter here.
"""
        fm, body = simple_yaml.parse_frontmatter(content)

        self.assertEqual(fm, {})
        self.assertIn("# Regular Markdown", body)


class TestSerializeFrontmatter(unittest.TestCase):
    """Test serialize_frontmatter() - create markdown with YAML frontmatter."""

    def test_serialize_basic_frontmatter(self):
        """Should create valid markdown with frontmatter."""
        fm = {
            "title": "Test",
            "tags": "global"
        }
        body = "\nBody content."

        result = simple_yaml.serialize_frontmatter(fm, body)

        self.assertTrue(result.startswith("---\n"))
        self.assertIn("title:", result)
        self.assertIn("tags:", result)
        self.assertIn("\nBody content.", result)

    def test_serialize_preserves_string_type(self):
        """Should write string fields correctly."""
        fm = {
            "title": "Test",
            "tags": "global, skills/test"
        }
        body = "\nBody."

        result = simple_yaml.serialize_frontmatter(fm, body)

        # Parse back and verify
        fm_read, body_read = simple_yaml.parse_frontmatter(result)

        self.assertIsInstance(fm_read["title"], str)
        self.assertIsInstance(fm_read["tags"], str)
        self.assertEqual(fm_read["tags"], "global, skills/test")

    def test_serialize_preserves_integer_type(self):
        """Should write integer fields as integers."""
        fm = {
            "title": "Test",
            "count": 42,
            "version": 2
        }
        body = "\nBody."

        result = simple_yaml.serialize_frontmatter(fm, body)
        fm_read, _ = simple_yaml.parse_frontmatter(result)

        self.assertIsInstance(fm_read["count"], int)
        self.assertEqual(fm_read["count"], 42)
        self.assertIsInstance(fm_read["version"], int)
        self.assertEqual(fm_read["version"], 2)

    def test_serialize_preserves_boolean_type(self):
        """Should write boolean fields as booleans."""
        fm = {
            "title": "Test",
            "enabled": True,
            "deprecated": False
        }
        body = "\nBody."

        result = simple_yaml.serialize_frontmatter(fm, body)
        fm_read, _ = simple_yaml.parse_frontmatter(result)

        self.assertIsInstance(fm_read["enabled"], bool)
        self.assertIs(fm_read["enabled"], True)
        self.assertIsInstance(fm_read["deprecated"], bool)
        self.assertIs(fm_read["deprecated"], False)

    def test_round_trip_preserves_all_types(self):
        """Should preserve all field types through parse -> serialize cycle."""
        original = """---
title: "Test Decision"
count: 42
enabled: true
tags: global, skills/test
---

## Context

Body content here.
"""
        # Parse
        fm, body = simple_yaml.parse_frontmatter(original)

        # Serialize
        result = simple_yaml.serialize_frontmatter(fm, body)

        # Parse again
        fm_new, body_new = simple_yaml.parse_frontmatter(result)

        # Verify all types preserved
        self.assertIsInstance(fm_new["title"], str)
        self.assertIsInstance(fm_new["count"], int)
        self.assertIsInstance(fm_new["enabled"], bool)
        self.assertIsInstance(fm_new["tags"], str)

        # Verify values
        self.assertEqual(fm_new["title"], "Test Decision")
        self.assertEqual(fm_new["count"], 42)
        self.assertIs(fm_new["enabled"], True)
        self.assertEqual(fm_new["tags"], "global, skills/test")

        # Verify body preserved
        self.assertIn("## Context", body_new)
        self.assertIn("Body content here.", body_new)

    def test_serialize_empty_frontmatter(self):
        """Should handle empty frontmatter dict."""
        fm = {}
        body = "\nBody content."

        result = simple_yaml.serialize_frontmatter(fm, body)

        # Should just return body when frontmatter is empty
        self.assertEqual(result, body)

    def test_serialize_with_comma_separated_values(self):
        """Should handle comma-separated string values (like tags)."""
        fm = {
            "tags": "global, skills/test, skills/another"
        }
        body = "\nBody."

        result = simple_yaml.serialize_frontmatter(fm, body)
        fm_read, _ = simple_yaml.parse_frontmatter(result)

        self.assertEqual(fm_read["tags"], "global, skills/test, skills/another")


if __name__ == "__main__":
    unittest.main(verbosity=2)
