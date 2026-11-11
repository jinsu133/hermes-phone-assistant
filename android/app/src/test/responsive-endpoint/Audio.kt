package android.media
import com.mist.connect.Fixture
class AudioTimestamp { var framePosition=0L; var nanoTime=0L; companion object { const val TIMEBASE_MONOTONIC=0 } }
class AudioFormat { companion object { const val CHANNEL_IN_MONO=16; const val ENCODING_PCM_16BIT=2 } }
class MediaRecorder { class AudioSource { companion object { const val VOICE_RECOGNITION=6; const val VOICE_COMMUNICATION=7 } } }
class AudioRecord(source:Int, rate:Int, channel:Int, encoding:Int, buffer:Int) {
 companion object { const val STATE_INITIALIZED=1; const val RECORDSTATE_RECORDING=3; const val READ_NON_BLOCKING=1; const val SUCCESS=0
 fun getMinBufferSize(rate:Int, channel:Int, encoding:Int)=3200 }
 var frameOffset=0
 fun getTimestamp(t:AudioTimestamp,base:Int):Int = -1 // no native clock in this fixture
 val state=STATE_INITIALIZED
 var recordingState=0
 val audioSessionId=1
 init { Fixture.allocations++ }
 fun startRecording() { Fixture.starts++; recordingState=RECORDSTATE_RECORDING }
 fun read(out:ShortArray, offset:Int, size:Int, mode:Int):Int {
   check(Fixture.index < Fixture.frames.size) { "Fixture exhausted without endpoint" }
   val frame=Fixture.frames[Fixture.index]
   val n=minOf(size,frame.pcm.size-frameOffset)
   frame.pcm.copyInto(out,offset,frameOffset,frameOffset+n)
   frameOffset+=n
   if(frameOffset==frame.pcm.size){Fixture.index++;frameOffset=0;Fixture.current=frame;Fixture.clockMs+=frame.advanceMs}
   else Fixture.current=frame.copy(final=false)
   return n
 }
 fun stop() { Fixture.stops++; recordingState=0; if(Fixture.failStop) error("stop_fixture") }
 fun release() { Fixture.releases++; if(Fixture.failRelease) error("release_fixture") }
}
