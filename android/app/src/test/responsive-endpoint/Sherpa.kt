package com.k2fsa.sherpa.onnx
class OnlineTransducerModelConfig(encoder:String, decoder:String, joiner:String)
class OnlineModelConfig(transducer:OnlineTransducerModelConfig,tokens:String,modelType:String,numThreads:Int,provider:String,debug:Boolean)
class KeywordSpotterConfig(modelConfig:OnlineModelConfig,keywordsFile:String,keywordsScore:Float,keywordsThreshold:Float,numTrailingBlanks:Int)
class OnlineStream { fun acceptWaveform(input:FloatArray,rate:Int) {}; fun release() {} }
class KeywordResult(val keyword:String="")
class KeywordSpotter(assets:android.content.res.AssetManager,config:KeywordSpotterConfig) {
 private var ready=false
 fun createStream(extra:String)=OnlineStream()
 fun isReady(stream:OnlineStream):Boolean { ready=!ready; return ready }
 fun decode(stream:OnlineStream) {}
 fun getResult(stream:OnlineStream)=KeywordResult(if(com.mist.connect.Fixture.current.wake) "HEY_HERMES" else "")
 fun release() {}
}
