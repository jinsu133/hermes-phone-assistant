"""On-demand dual-role pairing check. No installation, audio or UI observation.

The companion checks the voice listener with its own saved key. A fresh HMAC
binds that result to this command, pairing revision and device. This is a
point-in-time transport check, never an action capability or voice readiness.
"""
import asyncio
import hashlib
import hmac
import json
import secrets
from pathlib import Path

from .backend import JsonMcpClient, parse_json
from .pairing import PairingError, canonical_path, public_status, read_bundle, ui_connection

DOMAIN = 'hermes-phone-pairing-check-v1'


def proof(key, nonce, bundle):
    fields = [DOMAIN, nonce, bundle['pairing_id'], str(bundle['revision']), bundle['device_instance_id']]
    return hmac.new(key.encode('ascii'), '\n'.join(fields).encode('ascii'), hashlib.sha256).hexdigest()


def snapshot(ctx, link, home):
    target = canonical_path(home)
    if ctx.get_config('connection_file') != str(target):
        raise PairingError('UNIFIED_PAIRING_REQUIRED')
    value = read_bundle(target)
    with link._lock:
        if (not link.listening or link._port != 5001 or not isinstance(link._token, str)
                or not hmac.compare_digest(link._token, value['voice']['token'])):
            raise PairingError('PAIRING_RUNTIME_NOT_APPLIED')
        return value, (link._generation, link._server, link._token)


async def verify(ctx, link, home):
    """Return only allowlisted metadata; do not persist a stale 'verified' flag."""
    try:
        value, owner = snapshot(ctx, link, home)
        nonce = secrets.token_hex(32)
        ui = ui_connection(value)
        port = int(ui['url'].split(':')[-1].split('/')[0])
        client = JsonMcpClient(ui, port)
        args = dict(nonce=nonce, pairing_id=value['pairing_id'], revision=value['revision'],
                    instance_id=value['device_instance_id'])
        async with asyncio.timeout(6):
            await client.initialize()
            raw = await client.rpc('tools/call', {'name': 'phone_pairing_probe', 'arguments': args})
        if raw.get('isError') is not False:
            raise PairingError('PAIRING_PEER_DENIED')
        parts = raw.get('content')
        if not isinstance(parts, list) or len(parts) != 1 or parts[0].get('type') != 'text':
            raise PairingError('PAIRING_PROOF_INVALID')
        result = parse_json(parts[0]['text'])
        if (type(result) is not dict or set(result) != set(args) | {'voice_authenticated', 'proof'}
                or any(type(result.get(k)) is not type(v) or result.get(k) != v for k, v in args.items())
                or result.get('voice_authenticated') is not True
                or not isinstance(result.get('proof'), str)
                or not hmac.compare_digest(result['proof'], proof(value['voice']['token'], nonce, value))):
            raise PairingError('PAIRING_PROOF_INVALID')
        current, current_owner = snapshot(ctx, link, home)
        if current != value or current_owner != owner:
            raise PairingError('PAIRING_CHANGED_DURING_CHECK')
        return dict(ok=True, **{**public_status(value), 'state': 'Verified',
                    'runtime_applied': True, 'verified': True},
                    verification_scope='ui_auth_and_app_to_voice_status',
                    point_in_time_only=True, voice_ready=link.voice_admission().get('ok') is True,
                    audio_tested=False, screen_tested=False)
    except asyncio.CancelledError:
        raise
    except PairingError as exc:
        return dict(ok=False, verified=False, error=str(exc))
    except TimeoutError:
        return dict(ok=False, verified=False, error='PAIRING_CHECK_TIMEOUT')
    except Exception:
        return dict(ok=False, verified=False, error='PAIRING_CHECK_UNAVAILABLE')
