# SHA-256 checksums of omitted Android dependencies

These checksums identify files previously verified in a local build candidate. They are not bundled in this source-only preview and are not an independent malware scan or license determination.

- `eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989`  `app/src/main/assets/vosk-model-small-ko-0.22.zip`. Matches the bytes downloaded on 2026-11-12 from `https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip`.
- `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`  `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`. Matches the SHA-256 published by the GitHub release API for `k2-fsa/sherpa-onnx` tag `v1.13.8`.
- `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`  `gradle/wrapper/gradle-wrapper.jar`. Matches `https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar`.
- `f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3`  `app/src/main/assets/sherpa-wake/decoder-epoch-12-avg-2-chunk-16-left-64.onnx`.
- `1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678`  `app/src/main/assets/sherpa-wake/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx`.
- `eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c`  `app/src/main/assets/sherpa-wake/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx`.

The three wake-model files above match the files in the upstream release archive `https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2`. Its README declares Apache License 2.0 and identifies its ModelScope source. Model redistribution questions have also been discussed in `https://github.com/k2-fsa/sherpa-onnx/issues/3802`; a checksum match alone is not a legal determination.

Notices currently included in `app/src/main/assets/licenses/` should be reviewed alongside each upstream model and library before redistribution. Matching an official checksum does not establish that all applicable license requirements have been met.
