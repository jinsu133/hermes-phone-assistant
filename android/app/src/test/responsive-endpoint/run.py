"""Sequential hardware-free JVM checks of actual capture/detector Kotlin call sites.
No Gradle/daemon, downloads, native inference, microphone, provider, or device calls.
"""
from pathlib import Path
import os, subprocess, sys, tempfile
fixture=Path(__file__).resolve().parent
root=fixture.parents[4]
source=root/'MistConnect-wake-probe/app/src/main/java/com/mist/connect'
baseline=sys.argv[1:]==['--baseline']
if sys.argv[1:] and not baseline: raise SystemExit('usage: run.py [--baseline]')
def production(name):
    if baseline and name in ('SpeechEndGate.java','HandsfreeCapture.kt','KoreanStopDetector.kt','KoreanWakeDetector.kt'):
        return root/'evidence/responsive-endpoint-20261023/baseline'/name
    return source/name
lab=root.parents[1]
cache=lab/'build-tools/gradle-home/caches/modules-2/files-2.1'
lib=lab/'build-tools/gradle-8.11.1/lib'
def jar(group,name,version):
    return str(next((cache/group/name/version).glob('*/*.jar')))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.0.21')
compiler=jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21')
compiler_cp=os.pathsep.join([compiler,stdlib,*[str(p) for pattern in ('kotlin-script-runtime-*.jar','kotlin-reflect-*.jar','trove4j-*.jar','annotations-*.jar','kotlinx-coroutines-core-jvm-*.jar') for p in lib.glob(pattern)]])
scratch=Path.home()/'.hermes/cache/scratch'
scratch.mkdir(parents=True,exist_ok=True)
os.environ['TMPDIR']=str(scratch)
with tempfile.TemporaryDirectory(prefix='responsive-endpoint-',dir=scratch) as temp:
    out=Path(temp)/'classes';out.mkdir()
    def run(args):
        print('COMMAND:', ' '.join(map(str,args)),flush=True)
        subprocess.run(list(map(str,args)),check=True)
    run(['javac','-J-Xmx192m','-d',out,*[production(n) for n in ['DuplexTurnHandoff.java','SpeechEndGate.java','CaptureCleanup.java','WakePhraseGate.java','StopPhraseGate.java','PlaybackReference.java','ContinuousCaptureHandoff.java','NativeDuplexApm.java','DuplexCaptureWorker.java']]])
    if not baseline:
        junit_cp=os.pathsep.join([str(out),str(lib/'junit-4.13.2.jar'),str(lib/'hamcrest-core-1.3.jar')])
        run(['javac','-J-Xmx192m','-cp',junit_cp,'-d',out,root/'MistConnect-wake-probe/app/src/test/java/com/mist/connect/SpeechEndGateTest.java'])
        run(['java','-Xmx128m','-cp',junit_cp,'org.junit.runner.JUnitCore','com.mist.connect.SpeechEndGateTest'])
    cp=os.pathsep.join([str(out),stdlib,str(lib/'gson-2.10.jar')])
    run(['java','-Xmx256m','-XX:ActiveProcessorCount=1','-Djava.io.tmpdir='+str(scratch),'-Dkotlin.colors.enabled=false','-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib','-no-reflect','-jvm-target','11','-classpath',cp,'-d',out,*[production(n) for n in ['HandsfreeCapture.kt','KoreanStopDetector.kt','KoreanWakeDetector.kt']],*sorted(fixture.glob('*.kt'))])
    run(['java','-Xmx128m','-Djava.io.tmpdir='+str(scratch),'-cp',cp,'com.mist.connect.ConnectedEndpointKt'])
