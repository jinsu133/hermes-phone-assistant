"""Conversation windows for phone voice runs.

All voice requests used to share one operator-configured session, so three weeks of
requests (mostly expired screen snapshots) were resent on every turn. A window keeps
follow-ups ("do that again") inside one session, and starts a fresh session after an
idle gap or a turn budget. Long-term recall stays with Hermes' own memory, which is
part of every session's system prompt, and with session_search.

The window id is the configured session id plus ".w<start epoch>". Only this plugin's
own state file is read or written; no Hermes session store is touched.
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

from .voice_run_ids import valid_session_id

DEFAULT_IDLE_MINUTES = 10
DEFAULT_MAX_TURNS = 20


def _setting(ctx, key, default, low, high):
    try:
        value = ctx.get_config(key, default)
    except Exception:
        return default
    return value if type(value) is int and low <= value <= high else default


class SessionWindow:
    def __init__(self, base, home, *, idle_seconds, max_turns, clock=time.time):
        self.base = base
        self.idle_seconds = idle_seconds
        self.max_turns = max_turns
        self.clock = clock
        self.path = Path(home) / 'state' / 'phone-runs' / 'session-window.json'
        self.state = self._load()

    @classmethod
    def from_ctx(cls, ctx, base, home, clock=time.time):
        return cls(base, home, clock=clock,
                   idle_seconds=60 * _setting(ctx, 'voice_session_idle_minutes', DEFAULT_IDLE_MINUTES, 1, 1440),
                   max_turns=_setting(ctx, 'voice_session_max_turns', DEFAULT_MAX_TURNS, 1, 500))

    def _load(self):
        try:
            with open(self.path, encoding='utf-8') as stream:
                data = json.load(stream)
        except (OSError, ValueError):
            return None
        if (type(data) is not dict or data.get('base') != self.base
                or type(data.get('start')) is not int or type(data.get('turns')) is not int
                or type(data.get('last_used')) not in (int, float)):
            return None
        return data

    def _save(self):
        tmp = self.path.with_name(self.path.name + '.tmp')
        try:
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(self.state, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(tmp, self.path)
        except OSError:
            # Losing the window only means the next request may open a new session.
            pass

    def _id(self, start):
        candidate = f'{self.base}.w{start}'
        return candidate if valid_session_id(candidate) else self.base

    def _expired(self, now):
        state = self.state
        return (state is None or now - state['last_used'] > self.idle_seconds
                or now < state['last_used'] - 60 or state['turns'] >= self.max_turns)

    @property
    def current(self):
        return self._id(self.state['start']) if self.state is not None else None

    def peek(self):
        """Session id the next request will use; nothing is recorded."""
        now = self.clock()
        if self._expired(now):
            # Never reuse the window that just ended, even within the same second.
            start = int(now) if self.state is None else max(int(now), self.state['start'] + 1)
            return self._id(start)
        return self._id(self.state['start'])

    def commit(self, session_id):
        """Record that a request was submitted to session_id (a value peek() returned)."""
        now = self.clock()
        if self.state is None or session_id != self._id(self.state['start']):
            start = int(session_id.rsplit('.w', 1)[1]) if session_id != self.base else int(now)
            self.state = {'base': self.base, 'start': start, 'turns': 0, 'last_used': now}
        self.state['turns'] += 1
        self.state['last_used'] = now
        self._save()

    def touch(self):
        """A request finished: idle time counts from its end, not its start."""
        if self.state is not None:
            self.state['last_used'] = self.clock()
            self._save()
