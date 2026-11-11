"""Hardware-free OFF/cleanup regression: exact service methods + real owner/driver.
No Gradle/APK/device/network/provider/native audio. Cached compiler/dependencies only.
Pass --service-source /path/to/saved/VoiceRequestService.kt to reproduce a baseline.
All generated classes and JVM temporary files remain under Hermes scratch.
"""
from pathlib import Path
import argparse
import os
import re
import subprocess
import tempfile

fixture = Path(__file__).resolve().parent
root = fixture.parents[4]
main = root / 'MistConnect-wake-probe/app/src/main/java/com/mist/connect'
tests = root / 'MistConnect-wake-probe/app/src/test/java/com/mist/connect'
lab = root.parents[1]
lib = lab / 'build-tools/gradle-8.11.1/lib'
cache = lab / 'build-tools/gradle-home/caches/modules-2/files-2.1'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--service-source', type=Path, default=main / 'VoiceRequestService.kt')
parser.add_argument('--microphone-source', type=Path, default=main / 'MicrophoneOwner.kt')
args = parser.parse_args()
service = args.service_source.read_text()

def between(start, end):
    begin = service.index(start)
    return service[begin:service.index(end, begin)]

slices = {
    '__DUPLEX__': ('    private fun newDuplexTurn(', '    private fun captureRejectionStatus('),
    '__QUIESCE__': ('    private fun quiescePlayback(', '    private fun onCaptureCleanup('),
    '__CAPTURE_CLEANUP__': ('    private fun onCaptureCleanup(', '    private fun invalidatePlayback('),
    '__INVALIDATE__': ('    private fun invalidatePlayback(', '    /** IO worker only; an ACK'),
    '__CANCEL_REMOTE__': ('    private fun cancelRemote(', '    private fun stopConversation('),
    '__CANCEL_ACTIVE__': ('    private fun cancelActive(', '    private fun finish('),
    '__FINISH__': ('    private fun finish(', '    private fun notification('),
    '__DESTROY__': ('    override fun onDestroy()', '    private class WipingByteArrayOutputStream'),
}
probe = (fixture / 'Probe.kt.in').read_text()
if 'private class RetainedPcm' not in service:
    probe = probe.replace('takeDuplexPcm(turn,1)?.pcm', 'takeDuplexPcm(turn,1)')
# Match production lifetime/lock identity, never an instance-local cancellation double.
shared = between('        private val cancellation =', '        @Volatile var activeRequestId')
probe = probe.replace('__CANCELLATION__', shared)
claim = re.search(r'if \(!(MicrophoneOwner\.claim\([^\n]*\))\) \{', service)
release = re.search(r'MicrophoneOwner\.release\([^\n]*\)', service)
assert claim is not None and release is not None, 'production microphone lease call sites missing'
probe = probe.replace('__MIC_CLAIM__', claim.group(1)).replace('__MIC_RELEASE__', release.group(0))
for marker, bounds in slices.items():
    probe = probe.replace(marker, between(*bounds))
# Keep the lifecycle proof/state and accepted-start reset source-derived too.
if '    private var localCleanupStartId:' in service:
    fields = between('    private var localCleanupStartId:', '    @Volatile private var playbackRequestScope')
    reset = between('        synchronized(cancellation) {\n            activeStartId = startId', '        handsfree = intent?.action')
else:
    fields = ''
    reset = '        activeStartId = startId\n'
probe = probe.replace('__OFF_FIELDS__', fields).replace('__START_RESET__', reset)
assert 'finally { playback.endListening(epoch, capture.cleanupConfirmed) }' in service
handsfree = between('    private fun handsfreeLoop(', '    private fun captureRejectionStatus(')
assert 'finally { finish(startId) }' in handsfree
assert '{ duplexMonitorReference() }' in handsfree
assert 'pendingDuplexPcm = takeDuplexPcm(duplexTurn, duplexRevision)' in handsfree

def jar(g, n, v):
    return next((cache / g / n / v).glob('*/*.jar'))

def cp(*paths):
    return os.pathsep.join(map(str, paths))

def run(command):
    print('COMMAND:', ' '.join(map(str, command)), flush=True)
    result = subprocess.run(list(map(str, command)), cwd=root / 'MistConnect-wake-probe/app', timeout=150)
    if result.returncode:
        raise SystemExit(result.returncode)

scratch = Path.home() / '.hermes/cache/scratch'
os.environ['TMPDIR'] = str(scratch)
with tempfile.TemporaryDirectory(dir=scratch, prefix='off-cleanup-jvm-') as tmp:
    tmp = Path(tmp)
    out = tmp / 'classes'
    out.mkdir()
    generated = tmp / 'Probe.kt'
    generated.write_text(probe)
    # Log is a test-only Android double; no Android native classes run on this JVM.
    log = tmp / 'Log.java'
    log.write_text('package android.util; public final class Log { public static int w(String tag,String text){return 0;} }')
    appjar = Path(os.environ.get('DUPLEX_APP_CLASSES', str(root / 'MistConnect-wake-probe/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar')))
    android = lab / 'build-tools/sdk/platforms/android-36/android.jar'
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.0')
    run(['javac', '-J-Xmx192m', '--release', '11', '-cp', cp(out, appjar, android), '-d', out,
         *sorted(main.glob('*.java')), log, root / 'offline-tests/duplex/VoiceDiagnostics.java',
         root / 'offline-tests/duplex/TeardownJoinProbe.java',
         root / 'offline-tests/duplex/CancellationContinuityProbe.java', root / 'offline-tests/duplex/NormalRetainedProbe.java'])
    deps = [jar('org.jetbrains.kotlin', n, '2.4.0') for n in
            ['kotlin-compiler-embeddable', 'kotlin-build-tools-api', 'kotlin-stdlib', 'kotlin-script-runtime', 'kotlin-daemon-embeddable']]
    deps += [jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
             jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'), lib / 'annotations-24.0.1.jar']
    compiler = ['java', '-Xmx256m', '-XX:ActiveProcessorCount=1', '-Dkotlin.colors.enabled=false',
                '-Djava.io.tmpdir=' + str(scratch), '-cp', cp(*deps), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-jvm-target', '11']
    run([*compiler, '-classpath', cp(out, stdlib), '-d', out, generated, args.microphone_source])
    # Retain existing cleanup, revocation, timeout and stale-response safety controls.
    names = ['ControllerLeaseTest', 'CaptureCleanupTest', 'CleanupOffRegressionTest',
             'ServiceLifecycleMigrationTest', 'NormalControllerCancellationTest']
    junit_cp = cp(out, lib / 'junit-4.13.2.jar', lib / 'hamcrest-core-1.3.jar')
    run(['javac', '-J-Xmx128m', '--release', '11', '-cp', junit_cp, '-d', out,
         *[tests / (name + '.java') for name in names]])
    run(['java', '-Xmx128m', '-Djava.io.tmpdir=' + str(scratch), '-cp', junit_cp,
         'org.junit.runner.JUnitCore', *['com.mist.connect.' + name for name in names]])
    native = ['-Dduplex.native=true', '-Djava.library.path='+os.environ['DUPLEX_NATIVE_DIR']] if os.environ.get('DUPLEX_NATIVE_DIR') else []
    run(['java', '-Xmx128m', *native, '-Djava.io.tmpdir=' + str(scratch), '-cp', cp(out, stdlib), 'com.mist.connect.ProbeKt'])
