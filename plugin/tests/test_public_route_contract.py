"""Review regressions for the gateway route's public API boundary."""
import ast
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[1] / 'voicelink.py'


class PublicGatewayRoute(unittest.TestCase):
    def test_gateway_does_not_access_plugin_manager_internals(self):
        tree = ast.parse(SOURCE.read_text(encoding='utf-8'))
        private = [n.lineno for n in ast.walk(tree)
                   if isinstance(n, ast.Attribute) and n.attr == '_manager']
        self.assertEqual(private, [], 'Use ctx.session_surface and ctx.inject_message')

    def test_gateway_does_not_query_core_state_database(self):
        tree = ast.parse(SOURCE.read_text(encoding='utf-8'))
        methods = [n.name for n in ast.walk(tree) if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]
        self.assertNotIn('_current_route_session_id', methods, 'Use session_key and bind public hook session_id')


if __name__ == '__main__':
    unittest.main(verbosity=2)
