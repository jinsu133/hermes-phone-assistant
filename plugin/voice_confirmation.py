"""Single-use voice decisions. Only fresh audio from the paired app can answer.

No model-callable approve tool, no text approval endpoint, no persistence/replay.
The existing phone tool waits for the app's verified execution result in the same turn.
"""
from __future__ import annotations
import hmac
import re
import secrets
import threading
import time
import unicodedata


def normalize(text):
    # Only whitespace, letter case and sentence-final punctuation vary; negation/additions never pass.
    # The phrase comes from the app in the user's language, so case and the Devanagari full stop count too.
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", str(text)).strip().rstrip(".!?。！？।")).casefold()


class VoiceConfirmation:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.RLock()
        self.pending = None

    def begin(self, request_id, action_id, prompt, phrase, digest):
        if not all(isinstance(v, str) and v for v in (request_id, action_id, prompt, phrase, digest)):
            raise ValueError("confirmation_invalid")
        if len(prompt) > 3500 or len(phrase) > 100 or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("confirmation_invalid")
        with self.lock:
            if self.pending is not None:
                raise ValueError("confirmation_busy")
            self.pending = dict(request_id=request_id, action_id=action_id, prompt=prompt,
                                phrase=phrase, digest=digest, audio_id=secrets.token_urlsafe(18),
                                expires=self.clock()+240, state="prompt", nonce=None)
            return dict(self.pending)

    def get(self, request_id, action_id):
        with self.lock:
            p = self.pending
            if not p or p["request_id"] != request_id or p["action_id"] != action_id:
                raise ValueError("confirmation_unknown")
            if self.clock() >= p["expires"]:
                p["state"] = "expired"
                raise ValueError("confirmation_expired")
            return dict(p)

    def arm(self, request_id, action_id):
        with self.lock:
            self.get(request_id, action_id)
            p = self.pending
            if p["state"] != "prompt":
                raise ValueError("confirmation_not_armable")
            p.update(state="listening", nonce=secrets.token_urlsafe(24))
            return {"nonce": p["nonce"], "action_id": action_id, "digest": p["digest"]}

    def claim_audio(self, request_id, action_id, nonce):
        with self.lock:
            self.get(request_id, action_id)
            p = self.pending
            if (p["state"] != "listening" or not isinstance(nonce, str)
                    or not nonce.isascii() or not hmac.compare_digest(p["nonce"], nonce)):
                raise ValueError("confirmation_replayed_or_unarmed")
            p["state"] = "transcribing"
            return dict(p)

    def finish_audio(self, request_id, action_id, transcript):
        with self.lock:
            self.get(request_id, action_id)
            p = self.pending
            if p["state"] != "transcribing":
                raise ValueError("confirmation_cancelled")
            allowed = normalize(transcript) == normalize(p["phrase"])
            p["state"] = "allowed" if allowed else "denied"
            return dict(allowed=allowed, action_id=action_id, request_id=request_id,
                        digest=p["digest"], state=p["state"])

    def clear(self, action_id=None):
        with self.lock:
            if action_id is None or (self.pending and self.pending["action_id"] == action_id):
                self.pending = None
