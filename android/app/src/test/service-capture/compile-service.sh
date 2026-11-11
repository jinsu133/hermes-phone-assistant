#!/bin/sh
# Type-check the full service against cached Android/app dependencies, not an APK build.
set -eu
python - "$0" <<'PY'
from pathlib import Path
import os, subprocess, sys, tempfile
root=Path(sys.argv[1]).resolve().parent.parents[4]
main=root/'MistConnect-wake-probe/app/src/main/java/com/mist/connect'
lab=root.parents[1];lib=lab/'build-tools/gradle-8.11.1/lib';cache=lab/'build-tools/gradle-home/caches/modules-2/files-2.1'
def jar(g,n,v):return next((cache/g/n/v).glob('*/*.jar'))
deps=[jar('org.jetbrains.kotlin',n,'2.4.0') for n in ['kotlin-compiler-embeddable','kotlin-build-tools-api','kotlin-stdlib','kotlin-script-runtime','kotlin-daemon-embeddable']]+[jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),lib/'annotations-24.0.1.jar']
compiler_cp=os.pathsep.join(map(str,deps));stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.0')
app=Path(os.environ.get('DUPLEX_APP_CLASSES',str(root/'MistConnect-wake-probe/app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar')))
android=lab/'build-tools/sdk/platforms/android-36/android.jar'
scratch=Path.home()/'.hermes/cache/scratch';scratch.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(dir=scratch,prefix='capture-service-compile-') as tmp:
 out=Path(tmp)/'classes';out.mkdir()
 transforms=lab/'build-tools/gradle-home/caches/8.11.1/transforms'
 native_api=[p for pattern in ['*/transformed/*sherpa*-runtime.jar','*/transformed/vosk-android-*-runtime.jar'] for p in transforms.glob(pattern)]
 native_api+=list((cache/'net.java.dev.jna/jna').glob('*/*/*.jar'))
 cp=os.pathsep.join(map(str,[out,app,android,stdlib,*native_api]))
 java_sources=[str(p) for p in main.glob('*.java')]
 commands=[['java','-Xmx256m','-XX:ActiveProcessorCount=1','-Dkotlin.colors.enabled=false','-Djava.io.tmpdir='+str(scratch),'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','11','-Xfriend-paths='+str(app),'-classpath',cp,'-d',str(out),*[str(main/n) for n in ['VoiceRequestService.kt','HandsfreeCapture.kt','MicrophoneOwner.kt','VoiceDiagnostics.kt','VoiceBridgeClient.kt','KoreanWakeDetector.kt','KoreanStopDetector.kt']],*java_sources],['javac','-J-Xmx192m','--release','11','-cp',cp,'-d',str(out),*java_sources]]
 # Kotlin resolves current Java source APIs first; javac then sees current Kotlin diagnostics.
 for c in commands:
  print('COMMAND: '+' '.join(c),flush=True)
  result=subprocess.run(c,cwd=root,timeout=150)
  print('EXIT:',result.returncode,flush=True)
  if result.returncode:sys.exit(result.returncode)
 assert (out/'com/mist/connect/VoiceRequestService.class').is_file()
 print('PASS full VoiceRequestService Kotlin compilation against cached Android/app dependencies; no APK')
PY
