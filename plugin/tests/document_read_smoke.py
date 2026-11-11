"""Exercise an installed plugin through the real Hermes loader/middleware/registry.

Uses an isolated temporary profile and CLI fixture owner, never the live Gateway.
The explicitly passed document is real; utterance delivery is a test fixture.
No STT/TTS/model calls, no non-loopback sockets, no live session mutation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import sys
import tempfile
from types import SimpleNamespace
from unittest import mock

parser = argparse.ArgumentParser()
parser.add_argument('--plugin-root', type=Path, required=True)
parser.add_argument('--document', type=Path, required=True)
args = parser.parse_args()


def offline(event, values):
    if event == 'socket.connect':
        raise PermissionError('No outgoing connections in document smoke test')


sys.addaudithook(offline)
with tempfile.TemporaryDirectory(prefix='document-smoke-') as temp:
    home = Path(temp)
    os.environ['HERMES_HOME'] = str(home)
    os.environ.pop('HERMES_SAFE_MODE', None)
    os.environ.pop('HERMES_ENABLE_PROJECT_PLUGINS', None)
    shutil.copytree(args.plugin_root, home/'plugins'/'hermes-phone-control',
                    ignore=shutil.ignore_patterns('__pycache__', 'tests'))
    (home/'config.yaml').write_text(json.dumps({'plugins': {
        'enabled':['hermes-phone-control'], 'entries': {'hermes-phone-control': {
            'allow_gateway_injection':True, 'settings': {
                'connection_file':str(home/'absent.json'),
                'voice_documents': {'project-status':str(args.document.absolute())}}}}}}))
    import model_tools
    from hermes_cli.plugins import PluginManager
    from hermes_cli.middleware import run_tool_execution_middleware
    from tools.registry import registry
    from agent.redact import redact_sensitive_text
    manager = PluginManager()
    manager.discover_and_load()
    owner = SimpleNamespace(_agent_running=False, session_id='document-smoke-fixture',
                            _pending_input=queue.Queue(), _interrupt_queue=queue.Queue())
    manager._cli_ref = owner
    link = manager._hooks['post_llm_call'][0].__self__
    module = sys.modules[link.__class__.__module__]
    pairing = home/'pairing.json'
    pairing.write_text(json.dumps({'voice_url':module.VOICE_URL,
        'voice_token':'fixture-document-smoke-01234567890123456789012345',
        'instance_id':'fixture-device'}))
    original = args.document.read_bytes()
    try:
        with mock.patch.object(module, 'PORT', 0):
            link.import_pairing(str(pairing)); link.start()
        status, accepted = link.say('등록된 작업 보고서를 끝까지 읽어 줘')
        assert status == 200 and accepted['accepted']
        manager.invoke_hook('pre_llm_call', session_id=owner.session_id, turn_id='read-document',
                            user_message=owner._pending_input.get_nowait())
        def call(values):
            with mock.patch('hermes_cli.plugins._delivery_manager', return_value=manager):
                return json.loads(run_tool_execution_middleware('voice_draft', values,
                    lambda a:registry.dispatch('voice_draft', a, session_id=owner.session_id),
                    session_id=owner.session_id, turn_id='read-document'))
        assert call({'action':'list_documents'})['result']['documents'] == ['project-status']
        page = call({'action':'read_document','name':'project-status'})['result']
        digest = page['sha256']; parts = [page['content']]; pages = 1
        while page['has_more']:
            page = call({'action':'read_document','name':'project-status',
                         'offset':page['next_offset'],'expected_sha256':digest})['result']
            parts.append(page['content']); pages += 1
        expected = redact_sensitive_text(original.decode('utf-8-sig'), force=True,
            file_read=True, secret_file=True, redact_url_credentials=True)
        assert ''.join(parts) == expected
        assert args.document.read_bytes() == original
        assert digest == hashlib.sha256(original).hexdigest()
        link.cancel_turn()
        assert not call({'action':'read_document','name':'project-status'})['ok']
        print(json.dumps({'ok':True, 'document':args.document.name, 'pages':pages,
            'unicode_chars':len(expected), 'sha256':digest, 'source_unchanged':True,
            'cancelled_read_denied':True, 'live_voice_test':False}))
    finally:
        link.stop(); manager.unload(); manager._cli_ref = None
