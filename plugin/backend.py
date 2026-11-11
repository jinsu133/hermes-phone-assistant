"""Bounded transport for the lab companion's JSON-only MCP subset (not general MCP).
No proxies, DNS, redirects, subprocesses, background workers or model calls.
"""
import asyncio
from dataclasses import asdict
import json
import math
import os
from pathlib import Path
import re
import socket
import stat
from types import SimpleNamespace
from urllib.parse import urlsplit
import uuid

from ._readonly.backend_session import BackendDenied, BackendSession
from ._readonly.mcp_readonly_backend import ReadOnlyMcpBackend

APP = 'com.mist.connect.hermeslab'
MAX_RESPONSE = 131072
MAX_TEXT = 6000


class Denied(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise Denied('INVALID_RESPONSE')
        result[key] = value
    return result


def parse_json(raw):
    return json.loads(raw, object_pairs_hook=unique_object)


def load_connection(filename):
    if not isinstance(filename, str) or not filename or not Path(filename).is_absolute():
        raise Denied('NOT_CONFIGURED')
    fd = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) & 0o077 or not 0 < info.st_size <= 4096):
            raise Denied('INVALID_CONNECTION')
        raw = stream.read(4097)
    if len(raw) > 4096:
        raise Denied('INVALID_CONNECTION')
    data = parse_json(raw)
    if isinstance(data, dict) and data.get('kind') == 'hermes-phone':
        from .pairing import ui_connection, PairingError
        try:
            data = ui_connection(data)
        except PairingError:
            raise Denied('INVALID_CONNECTION') from None
    if not isinstance(data, dict) or set(data) != {'version','url','instance_id','token'}:
        raise Denied('INVALID_CONNECTION')
    if type(data['version']) is not int or data['version'] != 1:
        raise Denied('INVALID_CONNECTION')
    url = urlsplit(data['url'])
    # Literal IPv4 avoids DNS/rebinding, aliases, userinfo, proxy and redirect leakage.
    if (url.scheme != 'http' or url.hostname != '127.0.0.1' or not url.port
            or data['url'] != f'http://127.0.0.1:{url.port}/mcp'):
        raise Denied('INVALID_CONNECTION')
    identity = data['instance_id']
    if not isinstance(identity,str) or str(uuid.UUID(identity)) != identity:
        raise Denied('INVALID_CONNECTION')
    if not isinstance(data['token'],str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}=',data['token']):
        raise Denied('INVALID_CONNECTION')
    return data, url.port


class JsonMcpClient:
    """Implements the two result shapes consumed by the reused read-only adapter."""
    def __init__(self, pairing, port):
        self.pairing = pairing
        self.port = port
        self.serial = 0

    async def rpc(self, method, params, notification=False):
        self.serial += 1
        request = {'jsonrpc':'2.0','method':method,'params':params}
        if not notification:
            request['id'] = self.serial
        body = json.dumps(request).encode()
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port,
                                                       family=socket.AF_INET, limit=16384)
        try:
            headers = (f'POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:{self.port}\r\n'
                       f'Authorization: Bearer {self.pairing["token"]}\r\n'
                       'Content-Type: application/json\r\nConnection: close\r\n'
                       f'Content-Length: {len(body)}\r\n\r\n').encode('ascii')
            writer.write(headers + body)
            await writer.drain()
            block = await reader.readuntil(b'\r\n\r\n')
            if len(block) > 16384:
                raise Denied('INVALID_RESPONSE')
            lines = block.decode('ascii').split('\r\n')
            code = int(lines[0].split(' ')[1])
            if code in (401, 403):
                raise Denied('AUTH_DENIED')
            if code != (202 if notification else 200):
                raise Denied('BACKEND_UNAVAILABLE')
            fields = {}
            for line in lines[1:]:
                if not line:
                    continue
                key, value = line.split(':', 1)
                key = key.lower()
                if key in fields:
                    raise Denied('INVALID_RESPONSE')
                fields[key] = value.strip()
            length = int(fields.get('content-length', '-1'))
            if 'transfer-encoding' in fields or not 0 <= length <= MAX_RESPONSE:
                raise Denied('RESPONSE_LIMIT')
            raw = await reader.readexactly(length)
            if notification:
                if raw:
                    raise Denied('INVALID_RESPONSE')
                return None
            response = parse_json(raw)
            if (not isinstance(response,dict) or response.get('jsonrpc') != '2.0'
                    or type(response.get('id')) is not int or response['id'] != self.serial
                    or 'error' in response or not isinstance(response.get('result'),dict)):
                raise Denied('INVALID_RESPONSE')
            return response['result']
        finally:
            # No wait_closed: the caller deadline must not wait on a malicious peer.
            writer.close()

    async def initialize(self):
        result = await self.rpc('initialize', {'protocolVersion':'2025-03-26',
            'capabilities':{},'clientInfo':{'name':'hermes-phone-control','version':'0.1.0'}})
        if result.get('protocolVersion') != '2025-03-26':
            raise Denied('INVALID_RESPONSE')
        await self.rpc('notifications/initialized', {}, notification=True)

    async def list_tools(self):
        result = await self.rpc('tools/list', {})
        return SimpleNamespace(tools=[SimpleNamespace(name=t['name']) for t in result['tools']])

    async def call_tool(self, name, arguments):
        if name not in {'phone_backend_status','get_ui_tree',
                        'phone_media_index_status','phone_media_search'}:
            raise Denied('DENIED')
        result = await self.rpc('tools/call', {'name':name,'arguments':arguments})
        if type(result.get('isError')) is not bool:
            raise Denied('INVALID_RESPONSE')
        return SimpleNamespace(isError=result['isError'], structuredContent=result.get('structuredContent'),
            content=[SimpleNamespace(**item) for item in result.get('content',[])])


def project_observation(ui, token):
    tree = parse_json(ui)
    root = tree.get('root')
    if not isinstance(root,dict) or root.get('packageName') != APP:
        raise Denied('FOREGROUND_DENIED')
    texts = []
    count = 0
    total = 0
    def visit(node, depth):
        nonlocal count, total
        count += 1
        if count > 512 or depth > 9:
            raise Denied('OBSERVATION_LIMIT')
        if not isinstance(node,dict) or node.get('packageName') != APP:
            return
        # Drop the whole subtree for input/password nodes, including labels.
        if node.get('editable') or node.get('password') or node.get('isPassword'):
            return
        for key in ('text','contentDescription'):
            text = node.get(key)
            if isinstance(text,str) and text.strip():
                text = text.replace(token, '[REDACTED]')[:500]
                if text not in texts:
                    total += len(text)
                    if total > MAX_TEXT or len(texts) >= 64:
                        raise Denied('OBSERVATION_LIMIT')
                    texts.append(text)
        children = node.get('children',[])
        if not isinstance(children,list):
            raise Denied('INVALID_RESPONSE')
        for child in children:
            visit(child, depth + 1)
    visit(root, 0)
    if not texts:
        raise Denied('OBSERVATION_UNAVAILABLE')
    return {'package':APP,'texts':texts,'sensitive':True,'untrusted_screen_data':True}


async def query(operation, filename, timeout):
    pairing, port = load_connection(filename)
    client = JsonMcpClient(pairing, port)
    backend = ReadOnlyMcpBackend(client)
    try:
        async with asyncio.timeout(timeout):
            await client.initialize()
            if operation == 'status':
                status = await backend.status()
                if status.instance_id != pairing['instance_id']:
                    raise Denied('DEVICE_MISMATCH')
                return {'status':asdict(status)}
            async with BackendSession(backend, pairing['instance_id'], timeout=timeout) as session:
                result = await session.observe()
                observation = project_observation(result['ui'], pairing['token'])
                return {'instance_id':pairing['instance_id'],'observation':observation,
                        'verification':'status_checked_before_and_after_tree'}
    finally:
        await backend.close()


