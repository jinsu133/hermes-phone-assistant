"""Deterministic, bounded repair of recurring Korean speech-recognition mistakes.

The on-device recognizer returns one free-form Korean sentence. Place names in
this user's speech come back wrong the same way each time ("가능역 A주차장" as
"가격력 a슈장"), so a user-confirmed mapping is more reliable than asking the
recognizer again or letting a model guess a destination.

Rules:
- A mapping is only ever created from an explicit user correction, never inferred.
- Matching ignores whitespace and Latin case, because the recognizer varies both.
- One pass over the text: at each position the longest matching key wins, and
  replaced spans are never re-scanned, so a mapping cannot feed another mapping.
- Nothing is written unless it validates; a damaged store is refused, not repaired.
"""
import json
import os
import tempfile
from pathlib import Path
from typing import Optional

VERSION = 1
MAX_ENTRIES = 200
MAX_TEXT = 60
MAX_STORE = 32768
FILENAME = "voice-corrections.json"


class CorrectionError(ValueError):
    pass


def store_path(home: Path) -> Path:
    return Path(home) / FILENAME


def squash(text: str) -> str:
    """Drop every whitespace character and fold Latin case; Korean is unaffected."""
    return "".join(text.split()).lower()


def _valid_text(value) -> bool:
    return (isinstance(value, str) and 0 < len(value) <= MAX_TEXT
            and not any(ord(ch) < 32 for ch in value))


def _entry(wrong, right) -> dict:
    if not (_valid_text(wrong) and _valid_text(right)):
        raise CorrectionError("correction_text_invalid")
    if squash(wrong) == squash(right):
        raise CorrectionError("correction_is_identity")
    return {"wrong": wrong, "right": right}


def _entries(raw: bytes) -> list:
    if len(raw) > MAX_STORE:
        raise CorrectionError("correction_store_too_large")
    try:
        data = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as exc:
        raise CorrectionError("correction_store_invalid") from exc
    if not isinstance(data, dict) or set(data) != {"version", "entries"}:
        raise CorrectionError("correction_store_invalid")
    if type(data["version"]) is not int or data["version"] != VERSION:
        raise CorrectionError("correction_store_invalid")
    if not isinstance(data["entries"], list) or len(data["entries"]) > MAX_ENTRIES:
        raise CorrectionError("correction_store_invalid")
    entries, seen = [], set()
    for item in data["entries"]:
        if not isinstance(item, dict) or set(item) != {"wrong", "right"}:
            raise CorrectionError("correction_store_invalid")
        entry = _entry(item["wrong"], item["right"])
        key = squash(entry["wrong"])
        if key in seen:
            raise CorrectionError("correction_store_invalid")
        seen.add(key)
        entries.append(entry)
    return entries


def load(home: Path) -> list:
    """Read the mapping table; a missing file is empty, a damaged one is refused."""
    path = store_path(home)
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except FileNotFoundError:
        return []
    except OSError as exc:
        raise CorrectionError("correction_store_unreadable") from exc
    try:
        with os.fdopen(fd, "rb") as stream:
            raw = stream.read(MAX_STORE + 1)
    except OSError as exc:
        raise CorrectionError("correction_store_unreadable") from exc
    return _entries(raw)


def save(home: Path, entries: list) -> None:
    payload = json.dumps({"version": VERSION, "entries": entries}, ensure_ascii=False,
                         sort_keys=True).encode("utf-8")
    if len(payload) > MAX_STORE:
        raise CorrectionError("correction_store_too_large")
    # Validate exactly what will be written, through the same reader.
    _entries(payload)
    path = store_path(home)
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix=".voice-corrections-", dir=str(path.parent))
    try:
        with os.fdopen(handle, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise
    directory = os.open(str(path.parent), os.O_RDONLY)
    try:
        os.fsync(directory)
    except OSError:
        pass
    finally:
        os.close(directory)


def put(home: Path, wrong: str, right: str) -> list:
    entry = _entry(wrong, right)
    entries = [item for item in load(home) if squash(item["wrong"]) != squash(entry["wrong"])]
    entries.append(entry)
    if len(entries) > MAX_ENTRIES:
        raise CorrectionError("correction_store_full")
    entries.sort(key=lambda item: (-len(squash(item["wrong"])), squash(item["wrong"])))
    save(home, entries)
    return entries


def remove(home: Path, wrong: str) -> list:
    key = squash(wrong)
    entries = [item for item in load(home) if squash(item["wrong"]) != key]
    save(home, entries)
    return entries


def apply(home: Optional[Path], text: str) -> tuple:
    """Return (repaired_text, applied_right_forms). Store damage leaves text untouched."""
    if not isinstance(text, str) or not text or home is None:
        return text, []
    try:
        entries = load(home)
    except CorrectionError:
        return text, []
    if not entries:
        return text, []
    # Keep original offsets so replacement preserves the speaker's spacing elsewhere.
    offsets, folded = [], []
    for position, char in enumerate(text):
        if char.isspace():
            continue
        offsets.append(position)
        folded.append(char.lower())
    haystack = "".join(folded)
    rules = sorted(((squash(e["wrong"]), e["right"]) for e in entries), key=lambda r: -len(r[0]))
    out, hits, cursor, index = [], [], 0, 0
    while index < len(haystack):
        for needle, right in rules:
            if needle and haystack.startswith(needle, index):
                out.append(text[cursor:offsets[index]])
                out.append(right)
                cursor = offsets[index + len(needle) - 1] + 1
                index += len(needle)
                hits.append(right)
                break
        else:
            index += 1
    if cursor == 0:
        return text, []
    out.append(text[cursor:])
    return "".join(out), hits
