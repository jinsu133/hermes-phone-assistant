"""Real Hermes loader/dispatcher + LOCAL FIXTURE HTTP, never the device."""
import copy
import json
import os
from pathlib import Path
import shutil
import socket
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
# Set isolation before ANY Hermes import; no active config, secrets or plugins.
TEMP = tempfile.TemporaryDirectory(prefix='phone-stage-a-')
# No active config, secrets or plugins: HERMES_HOME is set for this module's import of
# Hermes and again (with restore) around the class, so other suites in the same
# unittest-discover process do not leak into it or inherit it.
ISOLATED_ENV = {'HERMES_HOME': TEMP.name, 'HERMES_SAFE_MODE': None, 'HERMES_ENABLE_PROJECT_PLUGINS': None}
_SAVED_ENV = {key: os.environ.get(key) for key in ISOLATED_ENV}


def _apply_env(values):
    for key, value in values.items():
        if value is None: os.environ.pop(key, None)
        else: os.environ[key] = value


_apply_env(ISOLATED_ENV)
from hermes_cli.plugins import get_plugin_manager
from tools.registry import registry
from model_tools import handle_function_call
_apply_env(_SAVED_ENV)

PACKAGE = 'com.mist.connect.hermeslab'
IDENTITY = '11111111-1111-4111-8111-111111111111'
TOKEN = 'F' * 43 + '='  # deliberately synthetic
BASE_STATUS = dict(instance_id=IDENTITY, ready=True, unlocked=True, accessibility=True)
BASE_TREE = {'available': True, 'root': {'packageName': PACKAGE, 'text': 'Fixture home', 'children': [
    {'packageName': PACKAGE, 'editable': True, 'text': 'PRIVATE_INPUT'},
    {'packageName': 'unapproved.app', 'text': 'PRIVATE_OTHER'},
    {'packageName': PACKAGE, 'password': True, 'text': 'PRIVATE_PASSWORD'},
    {'packageName': PACKAGE, 'contentDescription': 'Fixture button'}]}}


class Fixture(BaseHTTPRequestHandler):
    requests = []
    state = BASE_STATUS.copy()
    tree = copy.deepcopy(BASE_TREE)
    mode = ''
    def log_message(self, *args):
        pass
    def do_POST(self):
        req = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        self.requests.append(req)
        if self.headers.get('Authorization') != 'Bearer ' + TOKEN:
            self.send_response(401); self.send_header('Content-Length', '0'); self.end_headers(); return
        if self.mode == 'redirect':
            self.send_response(302); self.send_header('Location', 'https://example.com/secret'); self.send_header('Content-Length','0'); self.end_headers(); return
        if self.mode == 'oversize':
            self.send_response(200); self.send_header('Content-Length','99999999'); self.end_headers(); return
        if self.mode == 'slow':
            time.sleep(1)
        method = req['method']
        if method == 'initialize':
            result = {'protocolVersion':'2025-03-26','capabilities':{'tools':{}},'serverInfo':{'name':'fixture','version':'1'}}
        elif method == 'notifications/initialized':
            self.send_response(202); self.send_header('Content-Length','0'); self.end_headers(); return
        elif method == 'tools/list':
            result = {'tools':[{'name':n} for n in ('phone_backend_status','get_ui_tree')]}
        else:
            name = req['params']['name']
            data = {'phone_backend_status':self.state, 'get_ui_tree':self.tree, 'phone_observe_screen':{'snapshot':'fixture','nodes':[]}, 'phone_action':{'dispatched':True,'verify_with_observe':True}}.get(name)
            result = {'isError':False,'content':[{'type':'text','text':json.dumps(data)}]}
            if name == 'get_ui_tree' and self.mode == 'lock_after':
                type(self).state = dict(self.state, unlocked=False)
        out = json.dumps({'jsonrpc':'2.0','id':req.get('id'),'result':result}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(out))); self.end_headers()
        try:
            self.wfile.write(out)
        except (BrokenPipeError, ConnectionResetError):
            pass


class StageA(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _apply_env(ISOLATED_ENV)
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), Fixture)
        cls.server.daemon_threads = True
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_port
        cls.connects = []
        cls.extra_loopback_ports = set()  # Test-owned ephemeral listeners only.
        cls.guard_network = True  # an audit hook cannot be removed; it is disarmed in tearDownClass
        def audit(event, args):
            if event == 'socket.connect' and cls.guard_network:
                address = args[1]
                if address != ('127.0.0.1', cls.port) and not (
                    address[0] == '127.0.0.1' and address[1] in cls.extra_loopback_ports
                ):
                    raise PermissionError('Test prohibits all non-fixture connections')
                cls.connects.append(address)
        sys.addaudithook(audit)
        target = Path(TEMP.name)/'plugins'/'hermes-phone-control'
        shutil.copytree(ROOT, target, ignore=shutil.ignore_patterns('__pycache__'))

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.thread.join()
        cls.guard_network = False
        _apply_env(_SAVED_ENV)

    def setUp(self):
        Fixture.requests.clear(); self.connects.clear()
        Fixture.state = BASE_STATUS.copy(); Fixture.tree = copy.deepcopy(BASE_TREE)
        Fixture.mode = ''
        self.connection = Path(TEMP.name)/'connection.json'
        self.pairing = dict(version=1,url=f'http://127.0.0.1:{self.port}/mcp',instance_id=IDENTITY,token=TOKEN)
        self.save_pairing()
        config = {'plugins':{'enabled':['hermes-phone-control'],'entries':{'hermes-phone-control':{'settings':{'connection_file':str(self.connection),'timeout_seconds':0.3, 'control_enabled':self._testMethodName.startswith('test_control_')}}}}}
        (Path(TEMP.name)/'config.yaml').write_text(json.dumps(config))
        # Hermes delivers hooks and tool middleware through its global manager.
        self.manager = get_plugin_manager()
        self.manager.discover_and_load()
        self.manager.invoke_hook('pre_llm_call', session_id='fixture-session',
                                 turn_id='fixture-turn', user_message='ordinary phone request')

    def tearDown(self):
        self.manager.unload()

    def save_pairing(self):
        self.connection.write_text(json.dumps(self.pairing)); self.connection.chmod(0o600)

    def call(self, name, args=None):
        raw = handle_function_call(name, {} if args is None else args, task_id='fixture-task', session_id='fixture-session', turn_id='fixture-turn')
        self.assertIsInstance(raw,str)
        self.assertNotIn(TOKEN,raw)
        for secret in ('PRIVATE_INPUT','PRIVATE_OTHER','PRIVATE_PASSWORD'):
            self.assertNotIn(secret,raw)
        return json.loads(raw)

    def test_registration_is_offline_and_native(self):
        self.assertIsNotNone(registry.get_entry('phone_status'))
        self.assertIsNotNone(registry.get_entry('phone_observe'))
        self.assertIsNone(registry.get_entry('phone_media_index_status'))
        self.assertIsNone(registry.get_entry('phone_media_search'))
        self.assertIsNone(registry.get_entry('phone_task'))
        self.assertEqual(self.connects,[])
        self.assertEqual(Fixture.requests,[])
        self.assertIn('phone-control', self.manager.list_plugin_skills('hermes-phone-control'))
        self.manager.unload()
        self.assertIsNone(registry.get_entry('phone_status'))

    def test_actions_disabled_without_optin(self):
        # Without opt-in the action tool is unavailable or refused; either way nothing is sent.
        result = self.call('phone_act', {'action':'home'})
        self.assertIn(result.get('error'), ('CONTROL_DISABLED', 'Unknown tool: phone_act'))
        self.assertEqual(Fixture.requests, [])

    def test_control_action_transport_and_observe(self):
        Fixture.state["action_confirmation_version"] = 1
        result = self.call('phone_act', {'action':'home'})
        self.assertTrue(result['ok']); self.assertTrue(result['result']['dispatched'])
        self.assertEqual(Fixture.requests[-1]['params'], {'name':'phone_action','arguments':{'action':'home'}})
        self.assertEqual(self.call('phone_observe')['result']['snapshot'], 'fixture')

    def test_control_old_backend_cannot_mutate_without_confirmation_gate(self):
        result=self.call('phone_act',{'action':'home'})
        self.assertEqual(result['error'],'CONFIRMATION_BACKEND_REQUIRED')
        self.assertFalse(any(x.get('params',{}).get('name')=='phone_action' for x in Fixture.requests))

    def test_control_rejects_arbitrary_args_before_network(self):
        for args in ({'action':'shell','text':'id'}, {'action':'click','ref':'n1'}, {'action':'home','url':'http://evil'}, {'action':'input_text','snapshot':'s','ref':'n1','text':False}):
            # Refused before any network call, by the confirmation guard or argument validation.
            error = self.call('phone_act',args)['error']
            self.assertTrue(error.startswith(('INVALID_ARGUMENTS', 'PHONE_CONFIRMATION_REQUIRED')), error)
        self.assertEqual(Fixture.requests, [])

    def test_control_lock_identity_prevents_action(self):
        for status in (dict(BASE_STATUS,unlocked=False), dict(BASE_STATUS,instance_id='wrong')):
            Fixture.state=status; Fixture.requests.clear()
            self.assertFalse(self.call('phone_act',{'action':'home'})['ok'])
            self.assertFalse(any(x.get('params',{}).get('name')=='phone_action' for x in Fixture.requests))

    def test_status_metadata_only(self):
        result = self.call('phone_status')
        self.assertTrue(result['ok'])
        self.assertEqual(result['status'],BASE_STATUS)
        self.assertNotIn('ui',result)
        self.assertFalse(any(r.get('params',{}).get('name')=='get_ui_tree' for r in Fixture.requests))

    def test_observe_projection_and_attribution(self):
        result = self.call('phone_observe')
        self.assertTrue(result['ok'])
        self.assertEqual(result['observation']['texts'],['Fixture home','Fixture button'])
        self.assertEqual(result['session_id'],'fixture-session')
        self.assertEqual(result['task_id'],'fixture-task')
        self.assertEqual(result['ownership'],'not_enforced_stage_a')

    def test_fail_closed_flags_identity_and_foreground(self):
        for key, value in [('ready',False),('unlocked',False),('accessibility',False),('instance_id','other')]:
            with self.subTest(key=key):
                Fixture.state = dict(BASE_STATUS, **{key:value}); Fixture.requests.clear()
                self.assertFalse(self.call('phone_observe')['ok'])
                self.assertFalse(any(r.get('params',{}).get('name')=='get_ui_tree' for r in Fixture.requests))
        Fixture.state = BASE_STATUS.copy()
        for tree in ({'available':False}, {'available':True,'root':{}}, {'available':True,'root':{'packageName':'unapproved.app','text':'PRIVATE_OTHER'}}):
            Fixture.tree = tree
            self.assertFalse(self.call('phone_observe')['ok'])
        Fixture.tree = copy.deepcopy(BASE_TREE); Fixture.mode = 'lock_after'
        self.assertFalse(self.call('phone_observe')['ok'])

    def test_auth_redirect_size_and_timeout(self):
        self.pairing['token'] = 'W'*43+'='; self.save_pairing()
        self.assertFalse(self.call('phone_status')['ok'])
        self.pairing['token'] = TOKEN; self.save_pairing()
        for mode in ('redirect','oversize','slow'):
            with self.subTest(mode=mode):
                Fixture.mode = mode
                started=time.monotonic()
                result=self.call('phone_status')
                self.assertFalse(result['ok'])
                self.assertLess(time.monotonic()-started,3)
                self.assertNotIn('example.com',json.dumps(result))

    def test_invalid_args_and_connection_fail_before_network(self):
        for args in ({'url':'https://example.com'}, {'allowlist':['*']}, {'text':True}):
            self.assertEqual(self.call('phone_observe',args)['error']['code'],'INVALID_ARGUMENTS')
        for url in ('https://example.com/mcp','http://localhost:5000/mcp','http://127.0.0.1:5000/mcp?x=1','http://user@127.0.0.1:5000/mcp'):
            self.pairing['url']=url; self.save_pairing()
            self.assertFalse(self.call('phone_status')['ok'])
        self.assertEqual(Fixture.requests,[])

    def test_status_locked_is_metadata_success(self):
        Fixture.state = dict(BASE_STATUS, unlocked=False)
        result = self.call('phone_status')
        self.assertTrue(result['ok'])
        self.assertFalse(result['status']['unlocked'])
        self.assertFalse(any(r.get('params',{}).get('name')=='get_ui_tree' for r in Fixture.requests))

    def test_projection_limits_and_token_removal(self):
        Fixture.tree['root']['text'] = TOKEN
        result = self.call('phone_observe')
        self.assertTrue(result['ok'])
        self.assertIn('[REDACTED]',result['observation']['texts'])
        Fixture.tree['root']['children'] = [{'packageName':PACKAGE,'text':str(i)+'x'*499} for i in range(20)]
        self.assertEqual(self.call('phone_observe')['error']['code'],'OBSERVATION_LIMIT')

    def test_async_cancellation_is_local_only(self):
        import asyncio
        Fixture.mode = 'slow'
        async def exercise():
            entry = registry.get_entry('phone_observe')
            task = asyncio.create_task(entry.handler({}, session_id='fixture-session'))
            await asyncio.sleep(0.05)
            task.cancel()
            result = json.loads(await task)
            self.assertEqual(result['error']['code'],'CANCELLED')
            self.assertEqual(result['ownership'],'not_enforced_stage_a')
        asyncio.run(exercise())

    def test_missing_or_insecure_connection(self):
        self.connection.chmod(0o644)
        self.assertFalse(self.call('phone_status')['ok'])
        self.connection.unlink()
        self.assertFalse(self.call('phone_status')['ok'])
        self.assertEqual(Fixture.requests,[])


if __name__ == '__main__':
    unittest.main(verbosity=2)
