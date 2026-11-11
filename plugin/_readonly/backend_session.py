"""Backend lifecycle contract. No Android transport or SDK startup is implemented here."""
import asyncio
from dataclasses import dataclass

class BackendDenied(RuntimeError):
    pass

@dataclass(frozen=True)
class BackendStatus:
    instance_id: str
    ready: bool
    unlocked: bool
    accessibility: bool

class BackendSession:
    def __init__(self, backend, expected_instance, timeout=5):
        if not expected_instance or timeout <= 0:
            raise ValueError('Explicit instance and positive timeout required')
        self.backend = backend
        self.expected_instance = expected_instance
        self.timeout = timeout
        self.state = 'new'
        self._lock = asyncio.Lock()
        self._close_task = None
        self.last_error = None

    async def _checked_status(self):
        status = await asyncio.wait_for(self.backend.status(), self.timeout)
        if not isinstance(status, BackendStatus):
            raise BackendDenied('Invalid status response')
        if status.instance_id != self.expected_instance:
            raise BackendDenied('Instance mismatch')
        if not all(flag is True for flag in (status.ready, status.unlocked, status.accessibility)):
            raise BackendDenied('Backend not ready, unlocked, or authorized')

    async def __aenter__(self):
        if self.state != 'new':
            raise BackendDenied('Session cannot be reused')
        self.state = 'opening'
        try:
            await self._checked_status()
            self.state = 'active'
            return self
        except BaseException:
            await self.aclose()
            raise

    async def observe(self):
        async with self._lock:
            if self.state != 'active':
                raise BackendDenied('Inactive session')
            try:
                await self._checked_status()
                result = await asyncio.wait_for(self.backend.observe(), self.timeout)
                if self.state != 'active':
                    raise BackendDenied('Session stopped during observation')
                if not isinstance(result, dict) or result.get('ok') is not True:
                    raise BackendDenied('Observation failed')
                if result.get('instance_id') != self.expected_instance:
                    raise BackendDenied('Observation instance mismatch')
                if not isinstance(result.get('ui'), str) or not result['ui'].strip():
                    raise BackendDenied('Empty observation')
                return result
            except BaseException:
                await self.aclose()
                raise

    async def _close(self):
        try:
            await asyncio.wait_for(self.backend.close(), self.timeout)
        except BaseException as exc:
            self.last_error = type(exc).__name__
            self.state = 'close_failed'
            raise
        else:
            self.state = 'closed'

    async def aclose(self):
        if self._close_task is None:
            self.state = 'closing'
            self._close_task = asyncio.create_task(self._close())
        await asyncio.shield(self._close_task)

    async def __aexit__(self, *exc):
        await self.aclose()
