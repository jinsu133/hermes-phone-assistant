# Reproducing the Android preview build

This is a source-only preview. The runtime models and binary build dependencies are deliberately not committed. Obtain each from the upstream project, verify its SHA-256 against `ASSET-CHECKSUMS.md`, and place it at the exact path listed there. Do not substitute unverified downloads. These checksums identify bytes; review third-party licensing separately.

## Source checkout

Use a new empty directory. On a Windows machine where Git's `core.autocrlf` may be enabled, preserve the source assets' line endings during the initial checkout:

```sh
git -c core.autocrlf=false clone https://github.com/jinsu133/hermes-phone-assistant.git
git -C hermes-phone-assistant config --local core.autocrlf false
```

The setting is limited to this checkout. A default Windows checkout with automatic CRLF conversion was observed to change the bytes of the packaged speech text assets, including `tokens.txt` and `keywords.txt`; it produced an APK payload different from the tested device candidate. This observation does not establish whether the altered files work at runtime.

## Omitted files

Paths below are relative to `android/` in the checkout. Obtain files only for your own build and check the applicable upstream terms.

1. `gradle/wrapper/gradle-wrapper.jar`: download from <https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar>.
2. `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar`: download from <https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar>.
3. `app/src/main/assets/vosk-model-small-ko-0.22.zip`: download from <https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip>.
4. The three `.onnx` files listed in `ASSET-CHECKSUMS.md`: obtain the upstream archive <https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2>. Extract only the three named regular files, verify each SHA-256, then place them under `app/src/main/assets/sherpa-wake/`. Preserve the repository's `tokens.txt` and `keywords.txt`; do not replace them with the archive's copies.

For example, `sha256sum path/to/file` prints a file's hash. Compare the entire 64-character result against the corresponding entry in `ASSET-CHECKSUMS.md` before building. The PCM test fixtures are also omitted and are not recovered by the steps above; do not assume all instrumentation tests can run.

## Build and evidence limits

Requirements include JDK 17, Android SDK 36, and Gradle 8.11.1 dependencies. Build from `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

On Windows use `gradlew.bat`. The first build may need network access to obtain Gradle and Android dependencies. The local reproduction used a previously populated Gradle cache and `--offline` on Windows; it does not demonstrate a clean online build on another machine.

At the original submission commit `7165a9d388744953add55aea9d9d97609a233826`, the local test using the verified upstream files built successfully and reported 47 suites / 307 tests / 0 failures / 0 errors. The resulting APK contained 94 payload entries, excluding signature metadata, matching the previously tested A36 candidate by content hash. A normal Windows checkout with CRLF conversion also passed those tests but differed in 16 text assets; it was not treated as byte-identical. Neither result demonstrates actual voice input, audible output, installation on your device, or catalog approval.
