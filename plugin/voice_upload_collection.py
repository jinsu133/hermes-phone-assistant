"""Explicit, ephemeral two-upload local test copies; never a recorder or provider.

Caller holds VoiceLink._lock. Consent is live in-memory only, never reconstructed
from configuration, summaries or files. No text, tokens or audio enter diagnostics.
"""
import os
from pathlib import Path
import stat
import time


class FixedPhraseUploadCollection:
    PHRASES = ('설정 열어줘', '홈으로 이동해줘')
    ARM_SECONDS = 120
    RETAIN_SECONDS = 600
    MAX_BYTES = 5 * 1024 * 1024

    def __init__(self, parent, generation, *, now=None):
        parent = Path(parent).absolute()
        info = parent.lstat()
        if (parent.resolve() != parent or not stat.S_ISDIR(info.st_mode)
                or info.st_uid != os.getuid() or info.st_mode & 0o077):
            raise ValueError('collection_parent_must_be_private_owned_directory')
        # A fixed exclusive directory bounds leftovers across crashes as well:
        # reopening never restores consent or silently accumulates another pair.
        self.directory = parent / 'phrase-two'
        self.directory.mkdir(mode=0o700)
        os.chmod(self.directory, 0o700)
        self.generation = generation
        self.deadline = (time.monotonic() if now is None else now) + self.ARM_SECONDS
        self.retention_deadline = self.deadline + self.RETAIN_SECONDS
        self.remaining = 2
        self.captured_phrases = set()
        self.files = []
        self.error = None

    def eligible(self, generation):
        return self.remaining > 0 and generation == self.generation and time.monotonic() < self.deadline

    def capture(self, audio, suffix, generation, transcript):
        if (not isinstance(transcript, str) or transcript not in self.PHRASES
                or transcript in self.captured_phrases or not self.eligible(generation)):
            return False
        if not 0 < len(audio) <= self.MAX_BYTES:
            return False
        # Consume the slot before the optional write. Never silently retry a failed
        # capture against a later, potentially differently consented utterance.
        self.remaining -= 1
        self.captured_phrases.add(transcript)
        path = self.directory / ('upload-%d%s' % (2 - self.remaining, suffix))
        fd = None
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            stream = os.fdopen(fd, 'wb')
            fd = None  # stream owns the descriptor from here onward.
            with stream:
                if stream.write(audio) != len(audio):
                    raise OSError('short_write')
                stream.flush()
            self.files.append(path)
            return True
        except OSError:
            if fd is not None:
                try:
                    os.close(fd)
                except OSError:
                    pass
            self.error = 'capture_io_failed'
            self.remaining = 0
            try:
                path.unlink(missing_ok=True)
            except OSError:
                self.error = 'capture_cleanup_failed'
            return False

    def purge(self):
        self.remaining = 0
        # Fixed bounded names only, including a partial write not in self.files.
        failed = False
        for index in (1, 2):
            for suffix in ('.wav', '.m4a', '.mp3', '.ogg', '.opus', '.flac', '.aac'):
                try:
                    (self.directory / ('upload-%d%s' % (index, suffix))).unlink(missing_ok=True)
                except OSError:
                    failed = True
        try:
            self.directory.rmdir()
        except OSError:
            failed = True
        if failed:
            self.error = 'capture_cleanup_failed'
        return not failed
