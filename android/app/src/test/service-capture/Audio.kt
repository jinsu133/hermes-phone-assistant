package android.media
import com.mist.connect.Fixture
class AudioFormat { companion object { const val CHANNEL_IN_MONO=16; const val ENCODING_PCM_16BIT=2 } }
class MediaRecorder { class AudioSource { companion object { const val VOICE_RECOGNITION=6 } } }
class AudioRecord(source:Int, rate:Int, channel:Int, encoding:Int, buffer:Int) {
 companion object { const val STATE_INITIALIZED=1; const val RECORDSTATE_RECORDING=3; const val READ_NON_BLOCKING=1
 fun getMinBufferSize(rate:Int, channel:Int, encoding:Int)=3200 }
 val state=STATE_INITIALIZED
 var recordingState=0
 fun startRecording() { recordingState=RECORDSTATE_RECORDING; Fixture.starts++ }
 fun read(out:ShortArray, offset:Int, size:Int, mode:Int):Int {
  check(++Fixture.reads<=400) { "unbounded fixture capture" }
  Fixture.buffer=out
  val n=Fixture.count
  java.util.Arrays.fill(out,offset,offset+n,1500.toShort())
  Fixture.clock+=Fixture.advance
  Fixture.onRead()
  return n
 }
 fun stop() { recordingState=0; Fixture.stops++; if(Fixture.failStop) error("stop_failed") }
 fun release() { Fixture.releases++; if(Fixture.failRelease) error("release_failed") }
}
