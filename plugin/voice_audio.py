"""Bounded, ordered provider outputs. No synthesis, transcoding, or network here."""
import os
from pathlib import Path
import stat

MAX_SEGMENTS = 16
SUFFIXES = {'.wav', '.m4a', '.mp3', '.ogg', '.opus', '.flac', '.aac'}


def read_segments(root, paths, maximum):
    if not isinstance(paths, list) or not 1 <= len(paths) <= MAX_SEGMENTS:
        raise ValueError('tts_output_invalid')
    root = Path(root)
    parts = []
    seen = set()
    total = 0
    for value in paths:
        if not isinstance(value, str):
            raise ValueError('tts_output_invalid')
        path = Path(value)
        try:
            relative = path.relative_to(root)
        except ValueError:
            raise ValueError('tts_output_invalid') from None
        if '..' in relative.parts or not relative.parts or path.suffix.lower() not in SUFFIXES:
            raise ValueError('tts_output_invalid')
        directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        fd = None
        try:
            for component in relative.parts[:-1]:
                child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory); directory = child
            fd = os.open(relative.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
            before = os.fstat(fd)
            identity = (before.st_dev, before.st_ino)
            if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size <= 0 or identity in seen:
                raise ValueError('tts_output_invalid')
            if before.st_size > maximum - total:
                raise ValueError('tts_audio_too_large')
            with os.fdopen(fd, 'rb') as stream:
                fd = None
                data = stream.read(maximum - total + 1)
                after = os.fstat(stream.fileno())
            current = os.stat(relative.name, dir_fd=directory, follow_symlinks=False)
            def signature(info):
                return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_nlink)
            if len(data) > maximum - total:
                raise ValueError('tts_audio_too_large')
            if len(data) != before.st_size or signature(before) != signature(after) or signature(after) != signature(current):
                raise ValueError('tts_output_invalid')
            seen.add(identity); total += len(data)
            parts.append((data, path.suffix.lower()))
        finally:
            if fd is not None: os.close(fd)
            os.close(directory)
    return parts


def cached_segment(parts, segment, protocol):
    if len(parts) > 1 and protocol < 5:
        return 409, {'ok': False, 'error': 'tts_segmented_reply_requires_client_update'}, ''
    if segment >= len(parts):
        return 400, {'ok': False, 'error': 'invalid_audio_segment'}, ''
    data, suffix = parts[segment]
    return 200, data, suffix
