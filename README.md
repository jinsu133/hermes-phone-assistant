# Hermes Phone Assistant

**A phone-native assistant for work, not a PC remotely controlling a phone.**

This project combines a Hermes Agent plugin running in Termux with an Android companion on the **same phone**. The companion implements an authenticated local MCP endpoint, an Android accessibility service and an optional voice overlay. The goal is to let a person speak a request, have Hermes carry out permitted phone actions, observe the result and reply aloud. The model provider selected in Hermes may still perform inference remotely; phone-local control does not mean the language model itself is on-device.

> Preview under active verification. A successful build or automated test is not evidence that voice, accessibility or an action succeeded on your device. Real-device acceptance and catalog review are separate gates.

## Repository layout

- `plugin/` — the Hermes plugin, including the local bridge, tools, hooks and tests.
- `android/` — companion app source; binary build dependencies and speech models are omitted from this public preview. Android is a separate application, not part of the catalog plugin subdirectory.
- `android/LICENSE` and `android/app/src/main/assets/licenses/` — upstream and bundled-component notices. See `plugin/NOTICE` as well.

## Architecture and consent

1. Hermes and the Android app connect over authenticated `127.0.0.1` loopback; ADB is not the delivered action transport.
2. Android accessibility and microphone access require Android's explicit permission. The overlay is optional and requires its own permission and opt-in.
3. Protected or unclassifiable actions can be denied. Financial effects, message sending and outgoing calls have explicit confirmation boundaries. The model must not supply its own approval.
4. Screen contents are untrusted input, not instructions or authorization. Re-observe the screen to verify the outcome of a dispatched action.
5. A model or speech provider can incur charges and transmit data according to its configuration. Voice and screen access are sensitive: use a trusted device and review the permission and provider settings.

## Building the Android app

**The source-only preview does not include the Gradle wrapper JAR, Android AAR, wake models, Vosk model, or PCM test fixtures.** A plain checkout cannot build or run all tests until the verified upstream dependencies have been obtained separately and placed at the expected paths. The checksums and upstream sources in `android/ASSET-CHECKSUMS.md` identify the omitted files; they do not resolve redistribution licenses. No binary dependency or model is published in this preview.

Once the missing dependencies are supplied, from `android/` with Android SDK 36, a compatible JDK, and an arm64 device, the intended build commands are:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

For Windows, use `gradlew.bat` instead. A debug build is not a Play Store release. Android's update path requires the same signing identity as the already-installed package; do not uninstall an existing app to bypass a signing mismatch, since that may destroy application data.

## Plugin status and catalog review

The plugin manifest is under `plugin/`, which is the catalog subdirectory. Inspect it and run `hermes plugins validate ./plugin` in a compatible Hermes environment. The Android companion and its permissions are required for actual phone actions.

Known review items: the current pairing flow reads an app-exported local credential bundle, and parts of the plugin call undocumented Hermes internals. These need to be reconciled with the current catalog's credential and public-interface rules; successful local validation alone does not establish catalog eligibility. The project does not claim catalog acceptance or completed real-device validation from the source tree alone.

## License

The project-authored code is offered under MIT; preserve the separate upstream MIT copyright and the third-party license notices in `android/` and `plugin/NOTICE`.
