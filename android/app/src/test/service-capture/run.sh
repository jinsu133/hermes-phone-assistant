#!/bin/sh
# Offline bounded JVM harness. Python below only extracts exact production source slices.
# No Gradle/APK/device/native engine/network/provider access. Run from any directory.
set -eu
python - "$0" "${1:-}" <<'PY'
from pathlib import Path
import os, subprocess, sys, tempfile
fixture=Path(sys.argv[1]).resolve().parent
root=fixture.parents[4]
source=root/'MistConnect-wake-probe/app/src/main/java/com/mist/connect'
tests=root/'MistConnect-wake-probe/app/src/test/java/com/mist/connect'
baseline=sys.argv[2]=='--baseline'
assert sys.argv[2] in ('','--baseline')
service=(root/'evidence/android-capture-service-20261023/baseline/VoiceRequestService.kt' if baseline else source/'VoiceRequestService.kt').read_text()
lab=root.parents[1]; lib=lab/'build-tools/gradle-8.11.1/lib'
cache=lab/'build-tools/gradle-home/caches/modules-2/files-2.1'
def jar(group,name,version):return str(next((cache/group/name/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.0.21')
compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21')
compiler_cp=os.pathsep.join([compiler,stdlib,*[str(p) for pattern in ('kotlin-script-runtime-*.jar','kotlin-reflect-*.jar','trove4j-*.jar','annotations-*.jar','kotlinx-coroutines-core-jvm-*.jar') for p in lib.glob(pattern)]])
def between(start,end):return service[service.index(start):service.index(end,service.index(start))]
manual=between('    private fun captureAndSend(', '    private fun submitAndSpeak(')
endpoint=between('                    val pcm = try {','                    if (pcm == null) break')
wiping=between('    private class WipingByteArrayOutputStream','    internal class MemoryAudioSource')
# Test-only observation: retain exact fill/reset/copy operations and decision code.
wiping=wiping.replace('private class','private inner class').replace('buf.copyOf(count).also { wipe() }','buf.copyOf(count).also { taken++; wipe() }').replace('            reset()','            wiped = buf.all { it.toInt() == 0 }; reset()')
status=between('    private fun captureRejectionStatus(', '    private fun captureAndSend(') if not baseline else ''
probe=(fixture/'Probe.kt.in').read_text().replace('__CAPTURE_METHOD__',manual).replace('__ENDPOINT_BLOCK__',endpoint).replace('__WIPING_CLASS__',wiping).replace('__STATUS_METHOD__',status)
state=between('                    val visible = if (message.startsWith', '                    main.post {') if not baseline else 'status=message'
probe=probe.replace('__STATE_BLOCK__',state)
if not baseline:
 assert 'if (!capture.cleanupConfirmed) throw rejected' in endpoint
 tail=between('                    if (pcm == null) break','                    val done = AtomicBoolean(false)')
 assert tail.index('if (pcm.isEmpty()) continue') < tail.index('turnStopped.set(false)')
 assert 'if (capture.wasStopped)' in tail and 'stopConversation(token)' in tail
 approval=between('            val pcm = try {\n                capture.nextRequest(true)', '            try {\n                if(capture.wasStopped)')
 assert 'catch (rejected: SpeechEndGate.Rejected)' in approval
 assert 'if (!capture.cleanupConfirmed) throw rejected' in approval
 assert 'finally { playback.endListening(ticket.epoch, capture.cleanupConfirmed) }' in approval
 print('PASS source guards: empty audio precedes acceptance; STOP separate; approval rejects with cleanup finally',flush=True)
scratch=Path.home()/'.hermes/cache/scratch';scratch.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='service-capture-',dir=scratch) as tmp:
 out=Path(tmp)/'classes';out.mkdir();generated=Path(tmp)/'Probe.kt';generated.write_text(probe)
 def run(args):
  print('COMMAND:', ' '.join(map(str,args)),flush=True)
  return subprocess.run(list(map(str,args))).returncode
 junit_cp=os.pathsep.join([str(out),str(lib/'junit-4.13.2.jar'),str(lib/'hamcrest-core-1.3.jar')])
 policies=['SpeechEndGate','CaptureCleanup','VoiceBridgeProtocol','StopPhraseGate','PhoneActionPolicy','VoiceConfirmationPolicy','ActionRiskPolicy','ActionApprovalGate','ConfirmationPolicy']
 testnames=['ConversationPolicyTest','PhoneActionPolicyTest','StructuralNavigationTest','VoiceConfirmationPolicyTest','ActionConfirmationTest','SpeechEndGateTest']
 assert run(['javac','-J-Xmx128m','--release','11','-cp',junit_cp,'-d',out,*[source/(n+'.java') for n in policies],*[tests/(n+'.java') for n in testnames]])==0
 junit=run(['java','-Xmx128m','-cp',junit_cp,'org.junit.runner.JUnitCore',*['com.mist.connect.'+n for n in testnames]])
 cp=os.pathsep.join([str(out),stdlib])
 assert run(['java','-Xmx256m','-XX:ActiveProcessorCount=1','-Djava.io.tmpdir='+str(scratch),'-Dkotlin.colors.enabled=false','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-classpath',cp,'-d',out,generated,fixture/'Audio.kt',fixture/'Clock.kt'])==0
 result=run(['java','-Xmx128m','-cp',cp,'com.mist.connect.ProbeKt'])
 sys.exit(result or junit)
PY
