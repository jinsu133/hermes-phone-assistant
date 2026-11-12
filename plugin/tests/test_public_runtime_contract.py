"""Review regression: the plugin must not depend on Hermes' private turn object."""
import ast
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'voicelink.py'


class PublicTurnBoundary(unittest.TestCase):
    def test_turn_admission_does_not_access_core_private_runtime(self):
        tree = ast.parse(SOURCE.read_text(encoding='utf-8'))
        forbidden_imports = [node.module for node in ast.walk(tree)
            if isinstance(node, ast.ImportFrom) and node.module == 'agent.relay_runtime']
        forbidden_attrs = [node.attr for node in ast.walk(tree)
            if isinstance(node, ast.Attribute) and node.attr == '_phone_voice_admission']
        self.assertEqual(forbidden_imports, [], 'Core relay runtime is not a public plugin API')
        self.assertEqual(forbidden_attrs, [], 'Admission belongs to plugin-owned state, not a core turn')


if __name__ == '__main__':
    unittest.main(verbosity=2)
