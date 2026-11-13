"""A refused confirmation names the one field that was not on screen."""
import asyncio
import json
import sys
import types
import unittest
from pathlib import Path
from unittest import mock

_PLUGIN_DIR = Path(__file__).resolve().parents[1]
if 'plugin' not in sys.modules:
    _pkg = types.ModuleType('plugin')
    _pkg.__path__ = [str(_PLUGIN_DIR)]
    sys.modules['plugin'] = _pkg

from plugin import actions  # noqa: E402

META = {"instance_id": "i", "ready": True, "unlocked": True, "accessibility": True,
        "action_confirmation_version": 1}


def client_returning(error_body):
    class Client:
        def __init__(self, *_): pass
        async def initialize(self): pass
        async def rpc(self, method, params):
            if params["name"] == "phone_backend_status":
                return {"isError": False, "content": [{"text": json.dumps(META)}]}
            return {"isError": True, "content": [{"text": json.dumps(error_body)}]}
    return Client


class MissingFieldTest(unittest.TestCase):
    def call(self, body):
        with mock.patch.object(actions, "load_connection", return_value=({"instance_id": "i"}, 1)), \
             mock.patch.object(actions, "JsonMcpClient", client_returning(body)):
            with self.assertRaises(actions.Denied) as caught:
                asyncio.run(actions.query_control("act", {"action": "click", "snapshot": "s", "ref": "n1"}, "f"))
        return caught.exception.code

    def test_missing_field_is_named(self):
        self.assertEqual(self.call({"code": "CONFIRMATION_DETAILS_REQUIRED", "missing": "recipient"}),
                         "CONFIRMATION_DETAILS_REQUIRED:recipient")

    def test_unknown_field_name_is_not_relayed(self):
        self.assertEqual(self.call({"code": "CONFIRMATION_DETAILS_REQUIRED", "missing": "x; drop"}),
                         "CONFIRMATION_DETAILS_REQUIRED")

    def test_other_codes_unchanged(self):
        self.assertEqual(self.call({"code": "UNSUPPORTED_UI", "missing": "recipient"}), "UNSUPPORTED_UI")


if __name__ == "__main__":
    unittest.main()
