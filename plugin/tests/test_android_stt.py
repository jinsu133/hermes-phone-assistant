"""Private-HOME registered-provider tests; loopback fixture only, no device/model/audio API."""
import copy
import json
import math
import os
from pathlib import Path
import shutil
import socket
import struct
import sys
import tempfile
import threading
import unittest
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
TMP = tempfile.TemporaryDirectory(prefix="android-stt-fixture-", dir=os.environ["TMPDIR"])
# Process-wide settings are applied only while this class runs (see setUpClass), so the
# module can share one unittest-discover process with the other suites.
ISOLATED_ENV = {"HOME": TMP.name, "HERMES_HOME": TMP.name, "HERMES_SAFE_MODE": None}
_SAVED_ENV = {key: os.environ.get(key) for key in ISOLATED_ENV}


def _apply_env(values):
    for key, value in values.items():
        if value is None: os.environ.pop(key, None)
        else: os.environ[key] = value


_apply_env(ISOLATED_ENV)  # also isolated while Hermes is imported
from hermes_cli.plugins import PluginManager
from agent.transcription_registry import get_provider
from tools.transcription_tools import transcribe_audio
_apply_env(_SAVED_ENV)

IDENTITY = "11111111-1111-4111-8111-111111111111"
TOKEN = "F" * 43 + "="


class Fixture(BaseHTTPRequestHandler):
    calls = []
    mode = "ok"
    def log_message(self, *args):
        pass
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        Fixture.calls.append((self.path, len(body), self.headers.get("Authorization")))
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_response(401); self.send_header("Content-Length", "0"); self.end_headers(); return
        if self.mode == "redirect":
            self.send_response(302); self.send_header("Location", "https://example.com/"); self.send_header("Content-Length", "0"); self.end_headers(); return
        value = dict(success=True, transcript="설정 앱 열어 줘", provider="android-native", on_device=True, instance_id=IDENTITY,
                     stt_contract=2, stt_reason="recognized", stt_cleanup_confirmed=True)
        if self.mode == "identity": value["instance_id"] = "other"
        if self.mode == "online": value["on_device"] = False
        if self.mode == "bool": value["success"] = 1
        if self.mode == "silence": value.update(success=False, transcript="", error="stt_no_match", stt_reason="no_match")
        if self.mode == "failure": value.update(success=False, transcript="", error="stt_korean_model_missing", stt_reason="failed")
        if self.mode == "unsafe_error": value.update(success=False, transcript="", error=TOKEN, stt_reason="failed")
        payload = json.dumps(value).encode()
        if self.mode == "oversize": payload = b" " * 40000
        self.send_response(200); self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)


class AndroidSttContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _apply_env(ISOLATED_ENV)
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        cls.port = cls.server.server_port
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True); cls.thread.start()
        cls.connects = []
        cls.guard_network = True  # an audit hook cannot be removed; it is disarmed in tearDownClass
        def audit(event, args):
            if event == "socket.connect" and cls.guard_network:
                addr = args[1]
                if not isinstance(addr, tuple) or addr != ("127.0.0.1", cls.port): raise RuntimeError("external_network_denied")
                cls.connects.append(addr)
        sys.addaudithook(audit)
        target = Path(TMP.name)/"plugins/hermes-phone-control"
        shutil.copytree(ROOT, target, ignore=shutil.ignore_patterns("__pycache__", "tests"))
        cls.connection = Path(TMP.name)/"connection.json"
        cls.connection.write_text(json.dumps(dict(version=1,url=f"http://127.0.0.1:{cls.port}/mcp",instance_id=IDENTITY,token=TOKEN)))
        cls.connection.chmod(0o600)
        (Path(TMP.name)/"config.yaml").write_text(json.dumps({"stt":{"enabled":True,"provider":"android-native","android-native":{"language":"ko-KR"}},"plugins":{"enabled":["hermes-phone-control"],"entries":{"hermes-phone-control":{"settings":{"connection_file":str(cls.connection)}}}}}))
        cls.manager = PluginManager(); cls.manager.discover_and_load()
        cls.provider = get_provider("android-native")
        assert cls.provider is not None, "public provider registration failed"
        assert not cls.connects, "registration must be offline"
        cls.wav = Path(TMP.name)/"fixture.wav"
        with wave.open(str(cls.wav),"wb") as stream:
            stream.setparams((1,2,16000,0,"NONE","not compressed"))
            stream.writeframes(b"".join(struct.pack("<h",int(2000*math.sin(i/10))) for i in range(16000)))
    @classmethod
    def tearDownClass(cls):
        cls.manager.unload(); cls.server.shutdown(); cls.server.server_close(); cls.thread.join(2)
        cls.guard_network = False
        _apply_env(_SAVED_ENV)
    def setUp(self):
        Fixture.mode = "ok"; Fixture.calls.clear()
    def test_registered_actual_dispatch(self):
        value = transcribe_audio(str(self.wav), source="fixture")
        self.assertTrue(value["success"],value); self.assertEqual(value["provider"],"android-native")
        self.assertEqual(value["transcript"],"설정 앱 열어 줘")
        self.assertEqual(Fixture.calls[0][:2],("/stt/ko",32000))
    def test_no_network_on_invalid_audio(self):
        bad=Path(TMP.name)/"bad.wav"; bad.write_bytes(b"not wave")
        self.assertFalse(self.provider.transcribe(bad)["success"]); self.assertEqual(Fixture.calls,[])
    def test_host_language_cannot_break_app_language(self):
        # The phone picks the app's language; a fixed host value is only a compatibility hint.
        for language in ["ko", "ko-KR", "en-US", "auto"]:
            self.assertTrue(self.provider.transcribe(self.wav,language=language)["success"])
    def test_model_and_bad_language_no_network(self):
        self.assertFalse(self.provider.transcribe(self.wav,language=["ko"])["success"])
        self.assertFalse(self.provider.transcribe(self.wav,model="whisper-1")["success"])
        self.assertEqual(Fixture.calls,[])
    def test_pairing_permission_fail_closed(self):
        self.connection.chmod(0o644)
        try: self.assertFalse(self.provider.transcribe(self.wav)["success"])
        finally: self.connection.chmod(0o600)
        self.assertEqual(Fixture.calls,[])
    def test_identity_offline_and_json_bool(self):
        for mode in ["identity","online","bool"]:
            Fixture.mode=mode; self.assertFalse(self.provider.transcribe(self.wav)["success"],mode)
    def test_redirect_not_followed(self):
        Fixture.mode="redirect"; self.assertFalse(self.provider.transcribe(self.wav)["success"])
        self.assertEqual(len(Fixture.calls),1)
    def test_response_bound(self):
        Fixture.mode="oversize"; self.assertFalse(self.provider.transcribe(self.wav)["success"])
    def test_no_speech(self):
        Fixture.mode="silence"; value=self.provider.transcribe(self.wav)
        # Contract v2: silence is a typed failure, never an empty success.
        self.assertFalse(value["success"]); self.assertEqual(value["error"], "stt_no_match")
    def test_whole_stop_variants_only(self):
        link = self.manager._hooks['post_llm_call'][0].__self__
        for word in ['스탑', '스톱', 'STOP!', '헤르메스 스탑']:
            code, value = link.say(word)
            self.assertEqual(code, 200)
            self.assertEqual(value['command_reason'], 'stop_only')
            self.assertFalse(value['accepted'])
        code, value = link.say('스톱이라는 단어를 설명해 줘')
        self.assertNotEqual(value.get('command_reason'), 'stop_only')
        self.assertEqual(Fixture.calls, [])
    def test_failure_no_fallback_or_secret_reflection(self):
        for mode in ["failure","unsafe_error"]:
            Fixture.mode=mode; value=self.provider.transcribe(self.wav)
            self.assertFalse(value["success"]); self.assertNotIn(TOKEN,json.dumps(value))
        self.assertEqual(len(Fixture.calls),2)

if __name__ == "__main__": unittest.main(verbosity=2)
