"""Android on-device STT via the paired companion; no cloud fallback.

The phone recognizes speech in the companion app's display language, so spoken approval
phrases (shown in that language) always meet a matching model. The host's language option
is accepted for compatibility but cannot change the recognizer language.

Registered only through the public TranscriptionProvider plugin contract. Registration
and availability checks open no network socket and never start a microphone.
"""
import http.client
import json
import wave
from agent.transcription_provider import TranscriptionProvider
from .backend import load_connection
from .stt_outcome import validated

MAX_PCM = 16000 * 2 * 30
MAX_RESPONSE = 32768
APP_LANGUAGES = ("en-US", "ko-KR", "ja-JP", "zh-CN", "es-ES", "fr-FR", "de-DE", "pt-BR",
                 "vi-VN", "id-ID", "th-TH", "hi-IN")


def language_hint_ok(language):
    # A fixed host value (for example "ko") must not break approvals in another app language.
    return language is None or (isinstance(language, str) and len(language) <= 35)


class AndroidTranscriptionProvider(TranscriptionProvider):
    def __init__(self, ctx):
        self.ctx = ctx

    @property
    def name(self):
        return "android-native"

    @property
    def display_name(self):
        return "Android on-device (app language)"

    def is_available(self):
        return isinstance(self.ctx.get_config("connection_file"), str)

    def list_models(self):
        return [{"id": "on-device-ko", "display": "Installed Android model for the app language",
                 "languages": list(APP_LANGUAGES), "max_audio_seconds": 30}]

    def default_model(self):
        return "on-device-ko"

    def transcribe(self, file_path, *, model=None, language=None, **extra):
        def fail(code):
            return {"success": False, "transcript": "", "provider": self.name, "error": code}
        if model not in (None, "", "on-device-ko"):
            return fail("android_stt_model_unsupported")
        if not language_hint_ok(language):
            return fail("android_stt_language_unsupported")
        conn = None
        pcm = None
        try:
            pairing, port = load_connection(self.ctx.get_config("connection_file"))
            # No decoder subprocess, temporary output, implicit resampling or network input.
            with wave.open(str(file_path), "rb") as source:
                if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, 16000, "NONE"):
                    return fail("android_stt_requires_pcm16_mono_16000_wav")
                count = source.getnframes()
                if not 160 <= count <= MAX_PCM // 2:
                    return fail("android_stt_audio_length_invalid")
                pcm = bytearray(source.readframes(count))
                if len(pcm) != count * 2:
                    return fail("android_stt_audio_truncated")
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=35)
            conn.request("POST", "/stt/ko", body=pcm,
                         headers={"Authorization": "Bearer " + pairing["token"],
                                  "Content-Type": "application/octet-stream",
                                  "Accept": "application/json", "Connection": "close"})
            response = conn.getresponse()
            if response.status != 200:
                return fail("android_stt_http_" + str(response.status))
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                return fail("android_stt_response_too_large")
            value = json.loads(raw)
            if (not isinstance(value, dict) or value.get("instance_id") != pairing["instance_id"]
                    or value.get("provider") != self.name or value.get("on_device") is not True
                    or type(value.get("success")) is not bool):
                return fail("android_stt_response_invalid")
            return validated(value)
        except (wave.Error, EOFError):
            return fail("android_stt_wav_invalid")
        except Exception:
            return fail("android_stt_unavailable")
        finally:
            if conn is not None:
                conn.close()
            if pcm is not None:
                pcm[:] = b"\0" * len(pcm)
