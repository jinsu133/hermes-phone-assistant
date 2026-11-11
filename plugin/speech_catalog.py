"""Read-only available-adapter catalog, NOT a host-wide readiness API.

Public registries enumerate *already registered* plugin objects. Built-ins and
PCM capabilities have no safe unified public catalog; inspect installed source
without importing dispatchers, resolving secrets, or calling provider methods.
`selectable` means a known selection can be SAVED, not that runtime is wired.
All runtime_ready values remain false: runtime must separately bind an isolated
credential resolver, exact adapter and negotiated audio format. See evidence docs.
"""
import ast
import hashlib
import importlib.util
from pathlib import Path
import re

_IDENTIFIER = re.compile(r'[a-z][a-z0-9_-]{0,63}\Z')
# Reviewed class AST fingerprints. Drift becomes unknown, never a guessed claim.
_STREAMS = {
    'ElevenLabsStreamer': ('elevenlabs', 'incremental_pcm', '8181b73d58c84e3a35528871ce3532b43788d85bdbc8f20298f139287696bd0f'),
    'OpenAIStreamer': ('openai', 'incremental_pcm', '710d27e4ac8bbe6bd4cb03ba135262a5317f0221d3ababaacfd5ba36b029fda3'),
    'GeminiStreamer': ('gemini', 'incremental_pcm', '677a18b3525efa4b37947506176ef5671964367eeef86e1e8afd5fa826d0a467'),
    # Public stream() drains _collect_async() before yielding the first byte.
    'XAIStreamer': ('xai', 'buffered_pcm', '24d3395ef0035f9f7df0b91dcdc83f091fd6c14a3a9d0cc98102f9790d70ba76'),
}
_CLOUD_STT = frozenset({'openai','groq','mistral','xai','elevenlabs','deepinfra'})


def _identifier(value):
    return type(value) is str and _IDENTIFIER.fullmatch(value) is not None


def _tree(root, relative):
    path = Path(root)/relative
    # Source-only, bounded reads; no config, credentials or executable imports.
    with path.open('rb') as stream:
        raw = stream.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024:
        raise ValueError('source_too_large')
    return ast.parse(raw), hashlib.sha256(raw).hexdigest()


def _evidence(relative, digest, symbol, line):
    return dict(source=relative, sha256=digest, symbol=symbol, line=line)


def _builtins(root, kind):
    relative, symbol = (('tools/tts_command_provider.py','BUILTIN_TTS_PROVIDERS') if kind=='tts'
                        else ('tools/transcription_common.py','BUILTIN_STT_PROVIDERS'))
    tree, digest = _tree(root, relative)
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t,ast.Name) and t.id==symbol for t in node.targets):
            call=node.value
            if not (isinstance(call,ast.Call) and isinstance(call.func,ast.Name) and call.func.id=='frozenset'
                    and len(call.args)==1 and not call.keywords):
                break
            values=ast.literal_eval(call.args[0])
            if type(values) not in (set,list,tuple) or not all(_identifier(v) for v in values):
                break
            return sorted(values), _evidence(relative,digest,symbol,node.lineno)
    raise ValueError('catalog_constant_unavailable')


def _row(kind, provider, origin, evidence):
    return dict(kind=kind, provider=provider, adapter_id=None, origin=origin,
                configured=False, registered=False, installed='source_present',
                dependencies='unknown', auth='unknown', languages=None,
                streaming='unknown', selectable=False, adapter_compatible=False,
                runtime_ready=False, evidence=evidence,
                blocked_reason='adapter_not_verified')


def _local_adapter(rows, filename, symbol, expected, kind, provider, streaming, languages):
    try:
        tree,digest=_tree(Path(__file__).parent,filename)
        node=next(n for n in tree.body if getattr(n,'name',None)==symbol)
        if hashlib.sha256(ast.dump(node,include_attributes=False).encode()).hexdigest()!=expected:
            return
    except (OSError,ValueError,SyntaxError,StopIteration):
        return
    row=_row(kind,provider,'phone_plugin',[_evidence(filename,digest,symbol,node.lineno)])
    row.update(adapter_id='phone:'+provider, streaming=streaming, languages=languages,
               selectable=True,adapter_compatible=True,blocked_reason='runtime_binding_and_readiness_required')
    if provider=='android-native': row['auth']='paired_device_required'
    if provider=='family-vertex': row['audiences']=['family']
    rows[kind,provider]=row


def discover(*, profile=None, scope=None, core_root=None):
    """Inspect declarations, never instantiate/probe/call cloud/load profile files.

    profile: optional caller-supplied dict containing tts/stt provider names and
      command-provider declarations. Commands/keys/other values are not echoed.
    scope: explicit Hermes profile scope key for public list_providers(). Defaults
      to the public registry's active scope. Scope does not filter global entries;
      registration therefore NEVER proves external credential isolation.
    core_root: installed source tree override (useful for offline tests).
    """
    if profile is not None and type(profile) is not dict:
        raise ValueError('SPEECH_PROFILE_INVALID')
    if scope is not None and (type(scope) is not str or not scope or '\x00' in scope):
        raise ValueError('SPEECH_SCOPE_INVALID')
    warnings=[]; rows={}
    if core_root is None:
        try:
            spec=importlib.util.find_spec('agent')
            core_root=Path(spec.origin).parent.parent if spec and spec.origin else None
        except (ImportError,ValueError,AttributeError):
            core_root=None
    if core_root is not None:
        for kind in ('tts','stt'):
            try:
                names,evidence=_builtins(core_root,kind)
                for name in names:
                    row=_row(kind,name,'core_builtin',[evidence])
                    row['streaming']='not_verified' if kind=='tts' else 'file_transcription'
                    # Native file-STT selection is saveable but NOT isolated execution.
                    if kind=='stt' and name in _CLOUD_STT | {'local'}:
                        row.update(adapter_id='core-stt:'+name, selectable=True,
                                   blocked_reason='credential_isolated_dispatch_required')
                    rows[kind,name]=row
            except (OSError,ValueError,SyntaxError,RecursionError):
                warnings.append(kind+'_builtin_inventory_unavailable')
        try:
            relative='tools/tts_streaming.py'; tree,digest=_tree(core_root,relative)
            for node in tree.body:
                if not isinstance(node,ast.ClassDef) or node.name not in _STREAMS:
                    continue
                name,streaming,expected=_STREAMS[node.name]
                row=rows.get(('tts',name))
                if row is None: continue
                observed=hashlib.sha256(ast.dump(node,include_attributes=False).encode()).hexdigest()
                if observed!=expected:
                    warnings.append(name+'_streaming_source_changed')
                    continue
                row.update(streaming=streaming,adapter_id='core-pcm:'+name,
                           selectable=streaming=='incremental_pcm',
                           blocked_reason=('pcm_transport_and_credential_isolation_required'
                                           if streaming=='incremental_pcm' else 'stream_buffers_before_first_yield'))
                row['evidence'].append(_evidence(relative,digest,node.name,node.lineno))
        except (OSError,ValueError,SyntaxError,RecursionError):
            warnings.append('streaming_inventory_unavailable')
    else:
        warnings.append('installed_source_unavailable')

    _local_adapter(rows,'android_stt.py','AndroidTranscriptionProvider',
                   'a76c925fe6369ed5bfc3499496d80c845fbaa3b9a5bb9dc300e264074dd793cf',
                   'stt','android-native','file_transcription',['ko-KR'])
    _local_adapter(rows,'voice_normal_stream.py','existing_vertex_opener',
                   '8f501ce8d689fb4db44ddb81fbf3b52f219a47b9bc31d5d8370c5d9da78d4380',
                   'tts','family-vertex','incremental_sse_pcm',None)
    # Import only the lightweight public registry API; no registration discovery,
    # no private dictionaries/functions, no provider availability/catalog methods.
    for kind,module_name in [('tts','agent.tts_registry'),('stt','agent.transcription_registry')]:
        try:
            module=__import__(module_name,fromlist=['list_providers'])
            providers=module.list_providers(scope=scope)
            for provider in providers:
                name=provider.name
                if not _identifier(name): continue
                row=rows.get((kind,name))
                if row is None:
                    row=_row(kind,name,'registered_plugin',[
                        dict(api=module_name+'.list_providers',scope='supplied' if scope else 'active')])
                    rows[kind,name]=row
                row['registered']=True
                row['installed']='registered_instance'
                # A stream override is NOT a verified incremental PCM contract.
        except (ImportError,AttributeError,TypeError,ValueError):
            warnings.append(kind+'_public_registry_unavailable')
    for kind in ('tts','stt'):
        section=(profile or {}).get(kind)
        if type(section) is not dict: continue
        configured=section.get('provider')
        declarations=section.get('providers',{})
        if type(declarations) is dict:
            for name,declaration in declarations.items():
                if not _identifier(name) or type(declaration) is not dict or declaration.get('type')!='command':
                    continue
                # Built-ins win; command config shadows a same-name plugin.
                if (kind,name) in rows and rows[kind,name]['origin']=='core_builtin': continue
                row=_row(kind,name,'configured_command',[dict(config_field=kind+'.providers.'+name+'.type')])
                row.update(installed='unknown',streaming='not_verified',blocked_reason='command_not_a_verified_stream_adapter')
                rows[kind,name]=row
        if _identifier(configured):
            if (kind,configured) not in rows:
                rows[kind,configured]=_row(kind,configured,'configured_unknown',[])
                rows[kind,configured]['installed']='unknown'
            rows[kind,configured]['configured']=True
    return dict(schema_version=1,catalog_kind='available_adapter_catalog',
                readiness_checked=False,providers=[rows[key] for key in sorted(rows)],warnings=warnings)
