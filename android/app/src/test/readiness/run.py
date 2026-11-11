"""Sequential hardware-free checks, following service-capture/responsive-endpoint harnesses.
Uses cached compiler/dependencies only. No Gradle, device, socket or provider access.
--baseline runs wiring assertions against the saved pre-change sources (expected RED).
"""
from pathlib import Path
import os, subprocess, sys, tempfile
fixture = Path(__file__).resolve().parent
root = fixture.parents[4]
main = root/'MistConnect-wake-probe/app/src/main/java/com/mist/connect'
tests = root/'MistConnect-wake-probe/app/src/test/java/com/mist/connect'
lab = root.parents[1]
lib = lab/'build-tools/gradle-8.11.1/lib'
cache = lab/'build-tools/gradle-home/caches/modules-2/files-2.1'
baseline = sys.argv[1:] == ['--baseline']
if sys.argv[1:] and not baseline: raise SystemExit('usage: run.py [--baseline]')
def jar(g, n, v): return next((cache/g/n/v).glob('*/*.jar'))
def cp(*paths): return os.pathsep.join(map(str, paths))
def run(args):
    print('COMMAND:', ' '.join(map(str,args)), flush=True)
    subprocess.run(list(map(str,args)), cwd=root/'MistConnect-wake-probe/app', check=True, timeout=180)
scratch = Path.home()/'.hermes/cache/scratch'
os.environ['TMPDIR'] = str(scratch)
with tempfile.TemporaryDirectory(dir=scratch, prefix='runs-readiness-') as tmp:
    out = Path(tmp)/'classes'; out.mkdir()
    junit_cp = cp(out,lib/'junit-4.13.2.jar',lib/'hamcrest-core-1.3.jar')
    if baseline:
        run(['javac','-J-Xmx128m','--release','11','-cp',junit_cp,'-d',out,tests/'SetupVoiceExecutionWiringTest.java'])
        run(['java','-Xmx128m','-Dphone.readiness.sources='+str(root/'evidence/android-runs-readiness/baseline'),'-cp',junit_cp,'org.junit.runner.JUnitCore','com.mist.connect.SetupVoiceExecutionWiringTest'])
        raise SystemExit(0)
    appjar = root/'MistConnect-wake-probe/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
    android = lab/'build-tools/sdk/platforms/android-36/android.jar'
    compile_cp = cp(out, appjar, android)
    run(['javac','-J-Xmx192m','--release','11','-cp',compile_cp,'-d',out,*sorted(main.glob('*.java'))])
    names = ['SetupReadinessTest','SetupVoiceExecutionTest','SetupVoiceExecutionWiringTest',
        'ScopedVoiceCancellationJUnitTest','ConversationPolicyTest','PhoneActionPolicyTest',
        'SpeechEndGateTest','StructuralNavigationTest','VoiceConfirmationPolicyTest',
        'EverydayPolicyTest','ReplyAudioSequenceTest']
    run(['javac','-J-Xmx128m','--release','11','-cp',junit_cp,'-d',out,
         *[tests/(name+'.java') for name in names], tests/'ScopedVoiceCancellationTest.java'])
    run(['java','-Xmx128m','-cp',junit_cp,'org.junit.runner.JUnitCore',*['com.mist.connect.'+name for name in names]])
    stdlib = jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.0')
    deps = [jar('org.jetbrains.kotlin',n,'2.4.0') for n in ['kotlin-compiler-embeddable','kotlin-build-tools-api','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable']]
    deps += [jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),lib/'annotations-24.0.1.jar']
    compiler = ['java','-Xmx256m','-XX:ActiveProcessorCount=1','-Dkotlin.colors.enabled=false','-Djava.io.tmpdir='+str(scratch),'-cp',cp(*deps),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11']
    fixture_out = Path(tmp)/'fixture'; fixture_out.mkdir()
    fixture_cp = cp(fixture_out, out, stdlib, lib/'gson-2.10.jar')
    run([*compiler,'-classpath',fixture_cp,'-d',fixture_out,main/'VoiceBridgeClient.kt',*sorted(fixture.glob('*.kt'))])
    run(['java','-Xmx128m','-cp',fixture_cp,'com.mist.connect.ConnectedReadinessKt'])
    # Type-check current full service and current client together, using actual Android API stubs.
    # Fixture JSON/HTTP classes are deliberately excluded from this classpath.
    run([*compiler,'-Xfriend-paths='+str(appjar),'-classpath',cp(out,appjar,android,stdlib),'-d',out,
         main/'VoiceBridgeClient.kt',main/'VoiceRequestService.kt'])
    assert (out/'com/mist/connect/VoiceRequestService.class').is_file()
    print('PASS current service + client Kotlin compilation against cached Android/app dependencies; no APK',flush=True)
