"""Source-only ingress contract. Tiny WAV silence is NOT human/acoustic evidence.

Real packaged VoiceLink.hear/_hear/_inject and HTTP handler; configured STT function
and ctx injection effects are isolated stubs. No paid/model/TTS/device calls.
"""
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
from types import ModuleType, SimpleNamespace
import unittest
from unittest import mock
import urllib.error
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = '_sample_collection_source'
package = ModuleType(PACKAGE)
package.__path__ = [str(ROOT)]
sys.modules[PACKAGE] = package
spec = importlib.util.spec_from_file_location(PACKAGE + '.voicelink', ROOT / 'voicelink.py')
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
provider = ModuleType('tools.transcription_tools')
provider.transcribe_audio = lambda *a, **k: None
sys.modules['tools.transcription_tools'] = provider
ports = set()
def audit(event, args):
    if event == 'socket.connect':
        address = args[1]
        if not isinstance(address, tuple) or address[0] != '127.0.0.1' or address[1] not in ports:
            raise PermissionError('offline_contract_network_denied')
sys.addaudithook(audit)


def wav_fixture():
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(16000)
        output.writeframes(b'\x00\x00' * 32)
    return stream.getvalue()


class FixedPhraseCollectionContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='fixed-phrase-ingress-', dir=os.environ['TMPDIR'])
        self.home = Path(self.temp.name)
        os.chmod(self.home, 0o700)
        self.messages = []
        ctx = SimpleNamespace(get_config=lambda key, default=None: default,
            _manager=SimpleNamespace(_cli_ref=SimpleNamespace(session_id='isolated-session')),
            inject_message=lambda text, **kw: self.messages.append(text) or True)
        self.link = module.VoiceLink(ctx)
        self.link._home = self.home
        self.link._server = object()  # Live-owner sentinel; HTTP tests replace with real server.
        self.link._token = 'isolated-test-token-not-production-0123456789'
        self.audio = wav_fixture()
        self.stt_paths = []
        self.transcript = '설정 열어줘'
        def stt(path, source):
            self.assertEqual(source, 'voicelink')
            self.assertEqual(Path(path).read_bytes(), self.audio)
            self.stt_paths.append(path)
            return {'success': True, 'transcript': self.transcript}
        self.stt = mock.patch.object(provider, 'transcribe_audio', side_effect=stt)
        self.provider = self.stt.start()
        self.addCleanup(self.stt.stop)

    def tearDown(self):
        self.link.disarm_sample_collection()
        self.temp.cleanup()
        for path in self.stt_paths:
            self.assertFalse(Path(path).exists(), 'ordinary STT temporary upload leaked')

    def arm(self):
        result = self.link.arm_sample_collection(consent=True)
        self.assertEqual(result['limit'], 2)
        return self.link._sample_collection

    def complete(self):
        if self.link._pending:
            self.link.observe_reply(session_id='isolated-session', turn_id='fixture-turn',
                user_message=self.messages[-1], assistant_response='fixture complete')
            self.assertIsNone(self.link._pending)

    def upload(self):
        result = self.link.hear(self.audio, '.wav')
        self.assertEqual(result[0], 200)
        self.assertTrue(result[1]['accepted'])
        self.complete()
        return result

    def test_default_off_and_explicit_consent(self):
        self.upload()
        self.assertIsNone(self.link._sample_collection)
        self.assertEqual(list(self.home.iterdir()), [])
        with self.assertRaises(ValueError):
            self.link.arm_sample_collection()
        self.assertEqual(self.provider.call_count, 1)

    def test_two_limit_real_hear_stt_inject_and_private_bytes(self):
        collection = self.arm()
        self.upload()
        self.transcript = '홈으로 이동해줘'
        self.upload()
        self.transcript = '홈으로 이동해줘'
        self.upload()
        files = sorted(collection.directory.iterdir())
        self.assertEqual(len(files), 2)
        self.assertEqual(collection.remaining, 0)
        self.assertEqual(collection.directory.stat().st_mode & 0o777, 0o700)
        for path in files:
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.read_bytes(), self.audio)
        self.assertEqual(self.provider.call_count, 3)
        self.assertEqual(len(self.messages), 3)
        self.assertEqual(collection.generation, self.link._generation)

    def test_invalid_empty_suffix_and_too_large_do_not_consume(self):
        collection = self.arm()
        for audio, suffix, code in [(b'', '.wav', 400), (self.audio, '.bad', 400),
                                   (b'x' * (module.MAX_BODY + 1), '.wav', 413)]:
            self.assertEqual(self.link.hear(audio, suffix)[0], code)
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(list(collection.directory.iterdir()), [])
        self.provider.assert_not_called()

    def test_nonmatching_and_confirmation_excluded(self):
        collection = self.arm()
        for text in ('background talk', '"설정 열어줘"', '설정 열어줘 그리고 홈으로 이동해줘',
                     '설정 열어줘.', ' 설정 열어줘', '설정 열어줘 ', '헤르메스 설정 열어줘'):
            self.transcript = text
            self.upload()
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(list(collection.directory.iterdir()), [])
        result = self.link.hear_confirmation(self.audio, '.wav', 'absent', 'absent', 'absent')
        self.assertEqual(result[0], 409)
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(list(collection.directory.iterdir()), [])

    def test_duplicate_does_not_exhaust_other_phrase_slot(self):
        collection = self.arm()
        self.upload(); self.upload()
        self.assertEqual(collection.remaining, 1)
        self.assertEqual(len(collection.files), 1)
        self.transcript = '홈으로 이동해줘'
        self.upload()
        self.assertEqual(collection.remaining, 0)
        self.assertEqual(len(collection.files), 2)

    def test_expiry_and_retention_timer_callback(self):
        collection = self.arm()
        collection.deadline = module.time.monotonic() - 1
        self.upload()
        self.assertFalse(collection.directory.exists())
        self.assertIsNone(self.link._sample_collection)
        collection = self.arm()
        self.upload()
        self.transcript = '홈으로 이동해줘'
        self.upload()
        collection.retention_deadline = module.time.monotonic() - 1
        self.link._sample_collection_timer.function()
        self.assertFalse(collection.directory.exists())
        self.assertIsNone(self.link._sample_collection)

    def test_cancel_during_stt_cleans_copy_and_normal_temp(self):
        collection = self.arm()
        original = self.provider.side_effect
        def stt(path, source):
            result = original(path, source)
            self.link.cancel_turn()
            return result
        self.provider.side_effect = stt
        result = self.link.hear(self.audio, '.wav')
        self.assertEqual(result, (409, {'ok': False, 'error': 'voice_cancelled'}))
        self.assertFalse(collection.directory.exists())
        self.assertEqual(self.messages, [])

    def test_external_owner_generation_and_text_revoke(self):
        collection = self.arm()
        self.link._generation += 1  # Boundary negative control: unknown owner revision.
        self.upload()
        self.assertFalse(collection.directory.exists())
        collection = self.arm()
        self.assertEqual(self.link.say('new text owner')[0], 200)
        self.assertFalse(collection.directory.exists())
        self.complete()

    def test_partial_write_failure_preserves_normal_path_and_purges(self):
        collection = self.arm()
        import builtins
        real_fdopen = os.fdopen
        class PartialStream:
            def __init__(self, fd): self.stream = real_fdopen(fd, 'wb')
            def __enter__(self): return self
            def __exit__(self, *args): self.stream.close()
            def write(self, data):
                self.stream.write(data[:10]); self.stream.flush()
                raise OSError('isolated_partial_write')
        # Only the optional capture uses fdopen here. STT uses unchanged NamedTemporaryFile.
        with mock.patch('os.fdopen', side_effect=lambda fd, mode: PartialStream(fd)):
            self.upload()
        self.assertFalse(collection.directory.exists())
        self.assertIsNone(self.link._sample_collection)
        self.assertEqual(self.provider.call_count, 1)
        self.assertEqual(len(self.messages), 1)

    def test_open_failure_preserves_normal_path(self):
        collection = self.arm()
        real_open = os.open
        def fail_collection(path, flags, *args, **kwargs):
            if str(path).startswith(str(collection.directory)):
                raise OSError('isolated_capture_open_failure')
            return real_open(path, flags, *args, **kwargs)
        with mock.patch('os.open', side_effect=fail_collection):
            self.upload()
        self.assertFalse(collection.directory.exists())
        self.assertEqual(len(self.messages), 1)

    def test_private_parent_symlink_and_busy_refused(self):
        public = self.home / 'public'; public.mkdir(mode=0o755); public.chmod(0o755)
        self.link._home = public
        with self.assertRaises(ValueError): self.link.arm_sample_collection(consent=True)
        alias = self.home / 'alias'; alias.symlink_to(self.home, target_is_directory=True)
        self.link._home = alias
        with self.assertRaises(ValueError): self.link.arm_sample_collection(consent=True)
        self.link._home = self.home
        self.link._audio_lock.acquire()
        try:
            with self.assertRaises(ValueError): self.arm()
        finally: self.link._audio_lock.release()
        self.assertIsNone(self.link._sample_collection)

    def test_reconnect_real_start_stop_revokes_without_rearming(self):
        # Uses stock profile resolver against isolated HOME only. No real pairing.
        with mock.patch.dict(os.environ, {'HERMES_HOME': str(self.home)}):
            self.link._server = None
            self.link.ctx.get_config = lambda key, default=None: 0 if key == 'voice_bind_port' else default
            pairing = self.home / 'fixture-pairing.json'
            pairing.write_text(json.dumps({'voice_url': module.VOICE_URL,
                'voice_token': self.link._token, 'instance_id': 'isolated-device'}))
            self.link.import_pairing(str(pairing))
            self.link.start()
            collection = self.arm()
            self.upload()
            self.link.stop()
            self.assertFalse(collection.directory.exists())
            self.link.start()
            try:
                self.assertIsNone(self.link._sample_collection)
                self.upload()
                self.assertFalse((self.home / 'phrase-two').exists())
            finally:
                self.link.stop()

    def test_stt_failure_and_exception_still_delete_normal_temp(self):
        collection = self.arm()
        self.provider.side_effect = lambda path, source: self.stt_paths.append(path) or {'success': False, 'error': 'fixture_failure'}
        self.assertEqual(self.link.hear(self.audio, '.wav')[0], 502)
        self.assertEqual(len(list(collection.directory.iterdir())), 0)
        def fail(path, source):
            self.stt_paths.append(path)
            raise RuntimeError('fixture_provider_failure')
        self.provider.side_effect = fail
        with self.assertRaises(RuntimeError):
            self.link.hear(self.audio, '.wav')
        self.assertEqual(len(list(collection.directory.iterdir())), 0)
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(self.messages, [])

    def test_existing_directory_never_restores_consent_or_accumulates(self):
        directory = self.home / 'phrase-two'
        directory.mkdir(mode=0o700)
        with self.assertRaises(FileExistsError):
            self.arm()
        self.assertIsNone(self.link._sample_collection)
        self.upload()
        self.assertEqual(list(directory.iterdir()), [])

    def test_cleanup_failure_blocks_rearm_but_normal_stt_continues(self):
        collection = self.arm()
        self.upload()
        real_unlink = Path.unlink
        def fail_capture_unlink(path, *args, **kwargs):
            if path.parent == collection.directory:
                raise OSError('isolated_purge_failure')
            return real_unlink(path, *args, **kwargs)
        with mock.patch.object(Path, 'unlink', fail_capture_unlink):
            self.link.cancel_turn()
            self.assertEqual(collection.remaining, 0)
            self.assertEqual(collection.error, 'capture_cleanup_failed')
            with self.assertRaises(ValueError): self.arm()
            self.upload()
            self.assertEqual(len(list(collection.directory.iterdir())), 1)
        self.link.disarm_sample_collection()
        self.assertFalse(collection.directory.exists())

    def test_authenticated_http_fixed_phrase_ingress_and_oversized(self):
        # Real stock start() attaches the public endpoint to the loaded source link.
        env_patch = mock.patch.dict(os.environ, {'HERMES_HOME': str(self.home)})
        env_patch.start()
        self.link._server = None
        self.link.ctx.get_config = lambda key, default=None: 0 if key == 'voice_bind_port' else default
        pairing = self.home / 'fixture-pairing.json'
        pairing.write_text(json.dumps({'voice_url': module.VOICE_URL,
            'voice_token': self.link._token, 'instance_id': 'isolated-device'}))
        self.link.import_pairing(str(pairing))
        self.link.start()
        server = self.link._server
        ports.add(server.server_port)
        try:
            def post(path, data, auth=True, length=None):
                headers = {'X-Audio-Format': '.wav'}
                if auth: headers['Authorization'] = 'Bearer ' + self.link._token
                if length is not None: headers['Content-Length'] = str(length)
                request = urllib.request.Request('http://127.0.0.1:%d%s' % (server.server_port, path),
                    data=data, headers=headers, method='POST')
                try:
                    with urllib.request.urlopen(request, timeout=2) as response:
                        return response.status, json.loads(response.read())
                except urllib.error.HTTPError as error:
                    return error.code, json.loads(error.read())
            def operator(payload, auth=True):
                return post('/sample-collection', json.dumps(payload).encode(), auth=auth)
            self.assertEqual(operator({'action': 'status'}, auth=False)[0], 401)
            self.assertEqual(operator({'action': 'arm', 'consent': 'fixed-two-phrase-120s'}, auth=False)[0], 401)
            self.assertIsNone(self.link._sample_collection)
            self.assertFalse(operator({'action': 'status'})[1]['armed'])
            self.assertEqual(post('/audio', self.audio)[0], 200)
            self.complete()
            self.assertIsNone(self.link._sample_collection)
            for bad in ({'action': 'arm'}, {'action': 'arm', 'consent': True},
                        {'action': 'arm', 'consent': 'fixed-two-phrase-120s', 'path': '/arbitrary'},
                        {'action': 'status', 'extra': True}, {'action': 'delete'}, []):
                self.assertEqual(operator(bad)[0], 400)
            self.assertEqual(post('/sample-collection?extra=1', b'{}')[0], 403)
            self.assertEqual(post('/sample-collection', b'x' * 257)[0], 413)
            self.assertEqual(operator({'action': 'arm', 'consent': 'fixed-two-phrase-120s'})[0], 200)
            collection = self.link._sample_collection
            self.assertTrue(operator({'action': 'status'})[1]['armed'])
            self.assertEqual(operator({'action': 'arm', 'consent': 'fixed-two-phrase-120s'})[0], 409)
            self.provider.reset_mock()
            self.assertEqual(post('/audio', self.audio, auth=False)[0], 401)
            self.assertEqual(post('/audio', b'', length=module.MAX_BODY + 1)[0], 413)
            self.assertEqual(post('/confirmation/audio', self.audio)[0], 409)
            self.assertEqual(collection.remaining, 2)
            self.provider.assert_not_called()
            for text in ('설정 열어줘', '설정 열어줘', '홈으로 이동해줘'):
                self.transcript = text
                result = post('/audio', self.audio)
                self.assertEqual(result[0], 200)
                self.assertTrue(result[1]['accepted'])
                self.complete()
            self.assertEqual(len(list(collection.directory.iterdir())), 2)
            self.assertEqual(self.provider.call_count, 3)
            status = operator({'action': 'status'})[1]
            self.assertEqual(status['copies'], 2)
            self.assertFalse(status['armed'])
            real_unlink = Path.unlink
            def deny_delete(path, *args, **kwargs):
                if path.parent == collection.directory:
                    raise OSError('fixture_cleanup_uncertainty')
                return real_unlink(path, *args, **kwargs)
            with mock.patch.object(Path, 'unlink', deny_delete):
                self.assertEqual(operator({'action': 'disarm'})[0], 409)
                blocked = operator({'action': 'status'})[1]
                self.assertTrue(blocked['cleanup_blocked'])
                self.assertFalse(blocked['armed'])
                self.assertEqual(operator({'action': 'arm', 'consent': 'fixed-two-phrase-120s'})[0], 409)
            self.assertEqual(operator({'action': 'disarm'})[0], 200)
            self.assertFalse(operator({'action': 'status'})[1]['armed'])
            self.assertFalse(collection.directory.exists())
            self.assertEqual(operator({'action': 'arm', 'consent': 'fixed-two-phrase-120s'})[0], 200)
            new_collection = self.link._sample_collection
            self.assertEqual(post('/cancel', b'')[0], 200)
            self.assertFalse(new_collection.directory.exists())
            self.assertFalse(operator({'action': 'status'})[1]['armed'])
        finally:
            self.link.stop()
            ports.discard(server.server_port)
            env_patch.stop()

    def test_expiry_and_owner_revocation_during_stt_no_copy(self):
        for revoke in ('expire', 'owner'):
            collection = self.arm()
            def stt(path, source):
                self.stt_paths.append(path)
                self.assertEqual(list(collection.directory.iterdir()), [])
                if revoke == 'expire':
                    collection.deadline = module.time.monotonic() - 1
                else:
                    self.link._generation += 1
                return {'success': True, 'transcript': '설정 열어줘'}
            self.provider.side_effect = stt
            result = self.link.hear(self.audio, '.wav')
            if revoke == 'owner':
                self.assertEqual(result[0], 409)
                # Status observes revoked ownership and purges without rearm.
                self.link.sample_collection_status()
            else:
                self.assertEqual(result[0], 200)
                self.complete()
            self.assertFalse(collection.directory.exists())
            self.assertIsNone(self.link._sample_collection)

    def test_successful_confirmation_stt_matching_phrase_never_copied(self):
        collection = self.arm()
        # Fixture-only pending confirmation; real single-use nonce and route.
        self.link.confirmation.begin('fixture-request', 'fixture-action', 'fixture prompt',
                                     '설정 열어줘', 'a' * 64)
        nonce = self.link.confirmation.arm('fixture-request', 'fixture-action')['nonce']
        result = self.link.hear_confirmation(self.audio, '.wav', 'fixture-request',
                                            'fixture-action', nonce)
        self.assertEqual(result[0], 200)
        self.assertTrue(result[1]['allowed'])
        self.assertEqual(self.provider.call_count, 1)
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(list(collection.directory.iterdir()), [])
        self.assertEqual(self.messages, [])
        self.link.confirmation.clear()

    def test_no_speech_has_no_copy(self):
        collection = self.arm()
        self.provider.side_effect = lambda path, source: self.stt_paths.append(path) or {
            'success': True, 'transcript': ''}
        self.assertEqual(self.link.hear(self.audio, '.wav')[1]['no_speech'], True)
        self.assertEqual(collection.remaining, 2)
        self.assertEqual(list(collection.directory.iterdir()), [])



if __name__ == '__main__':
    unittest.main(verbosity=2)
