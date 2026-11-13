"""Voice runs always use the Hermes default profile's model; the phone can set only effort."""
import importlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
picker = importlib.import_module('plugin.voice_model_picker')
runs = importlib.import_module('plugin.voice_runs_integration')

CATALOG = {'provider': 'nous', 'model': 'default-model',
           'providers': [{'slug': 'nous', 'models': ['default-model', 'other'], 'authenticated': True}]}


class DefaultProfileModel(unittest.TestCase):
    def test_inherited_selection_with_effort_is_accepted(self):
        value = {'provider': None, 'model': None, 'reasoning_effort': 'high'}
        self.assertEqual(picker.validate_selection(value, CATALOG), value)

    def test_explicit_model_is_refused(self):
        for value in ({'provider': 'nous', 'model': 'other', 'reasoning_effort': None},
                      {'provider': None, 'model': 'other', 'reasoning_effort': None},
                      {'provider': 'nous', 'model': None, 'reasoning_effort': None}):
            with self.assertRaises(picker.SettingsError) as caught:
                picker.validate_selection(value, CATALOG)
            self.assertEqual(caught.exception.args[0], 'EXECUTION_MODEL_FOLLOWS_DEFAULT_PROFILE')

    def test_catalog_reports_model_source(self):
        self.assertEqual(picker.normalize_catalog(CATALOG)['model_source'], 'hermes_default_profile')
        self.assertEqual(runs.MODEL_SOURCE, 'hermes_default_profile')

    def test_submit_never_sends_saved_model(self):
        sent = {}

        class Owner:
            def begin(self, **kw):
                sent.update(kw)
                return 'ok'

        class Link:
            _session_generation = 1

        bridge = runs.RunsBinding.__new__(runs.RunsBinding) if hasattr(runs, 'RunsBinding') else None
        if bridge is None:
            self.skipTest('runs bridge class not exposed')
        bridge.owner, bridge.link, bridge.session_id = Owner(), Link(), 's'
        bridge.config = {'model': 'saved-old', 'provider': 'saved-old', 'model_options': {}}
        bridge.submit('hi', {'request_id': 'r', 'session_id': 's', 'digest': 'd'})
        self.assertIsNone(sent['model'])
        self.assertIsNone(sent['provider'])


if __name__ == '__main__':
    unittest.main()
