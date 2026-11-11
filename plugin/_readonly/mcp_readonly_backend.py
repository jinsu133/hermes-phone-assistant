"""Read-only adapter over an initialized MCP ClientSession.
phone_backend_status is a REQUIRED NEW companion extension, not an upstream MistConnect tool.
Transport ownership stays with the caller's async context.
"""
import json
from .backend_session import BackendStatus, BackendDenied

class ReadOnlyMcpBackend:
    def __init__(self, client):
        self.client=client
        self.closed=False
        self.checked=False

    async def _call(self,name,arguments):
        if self.closed:raise BackendDenied('MCP adapter closed')
        if name not in {'phone_backend_status','get_ui_tree'}:
            raise BackendDenied('Tool not allowed')
        result=await self.client.call_tool(name,arguments)
        if result.isError:raise BackendDenied('MCP tool failed')
        data=result.structuredContent
        if data is None:
            content=result.content
            if len(content)!=1 or content[0].type!='text':raise BackendDenied('Expected one JSON text block')
            try:data=json.loads(content[0].text)
            except (TypeError,ValueError) as exc:raise BackendDenied('Invalid JSON tool result') from exc
        if not isinstance(data,dict) or 'error' in data:raise BackendDenied('Invalid backend result')
        return data

    async def status(self):
        if self.closed:raise BackendDenied('MCP adapter closed')
        if not self.checked:
            listing=await self.client.list_tools()
            names={tool.name for tool in listing.tools}
            if not {'phone_backend_status','get_ui_tree'}<=names:
                raise BackendDenied('Companion status extension missing; raw upstream is unsupported')
            self.checked=True
        data=await self._call('phone_backend_status',{})
        keys={'instance_id','ready','unlocked','accessibility'}
        if not keys<=data.keys() or not isinstance(data['instance_id'],str):raise BackendDenied('Missing status fields')
        if any(type(data[k]) is not bool for k in keys-{'instance_id'}):raise BackendDenied('Invalid status flags')
        return BackendStatus(**{key:data[key] for key in keys})

    async def observe(self):
        before=await self.status()
        if not all((before.ready,before.unlocked,before.accessibility)):raise BackendDenied('Not authorized to observe')
        tree=await self._call('get_ui_tree',{'max_depth':8,'include_bounds':True})
        if tree.get('available') is not True or not isinstance(tree.get('root'),dict) or not tree['root']:
            raise BackendDenied('UI root unavailable')
        after=await self.status()
        if before!=after:raise BackendDenied('Status changed during observation')
        return {'ok':True,'instance_id':after.instance_id,'ui':json.dumps(tree,ensure_ascii=False)}

    async def close(self):
        # Local admission closure ONLY; owning transport context closes the MCP session.
        self.closed=True
