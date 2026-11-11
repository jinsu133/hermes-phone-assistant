package org.vosk
import com.mist.connect.Fixture
class Model(path:String) { fun close() {} }
class Recognizer(model:Model, rate:Float) {
 fun setWords(value:Boolean) {}; fun setPartialWords(value:Boolean) {}
 fun acceptWaveForm(pcm:ShortArray,count:Int):Boolean { Fixture.decodeCalls++; return Fixture.current.final }
 val result:String get()=Fixture.current.json(true)
 val partialResult:String get()=Fixture.current.json(false)
 fun reset() {}; fun close() {}
}
