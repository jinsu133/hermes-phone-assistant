# Hermes Phone Control plugin

This is the Hermes plugin subdirectory of the phone-native assistant. It pairs with an Android companion on the same phone over authenticated loopback. Hermes tools can request phone observation and bounded actions through the companion's MCP service; the Android app retains its own permission, device-lock and confirmation gates. ADB is not the production action path.

The plugin requires Hermes and the companion on the same Android/Termux device. The supported core baseline is **Hermes v0.21.4 (2026.9.21, upstream `dd2ae84a`)**, the last release confirmed to run correctly on Termux; the plugin uses only interfaces present in that release. The default manifest configuration leaves voice and control disabled until enabled by the operator. Microphone, accessibility and optional overlay permission require explicit Android consent.

## Setup

1. Install the companion app and grant microphone and accessibility access on the phone.
2. Import the pairing file the app exports: `/phone_setup import <file>`.
3. Pick a text-to-speech engine in Hermes (`hermes tools` → text to speech). Any engine Hermes supports works (Edge TTS, OpenAI, ElevenLabs, a local command, and so on); with a Nous Portal subscription, **Nous Subscription** uses the Tool Gateway and needs no separate key.
4. Speech recognition uses the phone's on-device recognizer through this plugin's own transcription provider; set `stt_model` / `stt_language` in the plugin settings if needed.
5. For a messaging-gateway session, set `session_key` and `plugins.entries.hermes-phone-control.allow_gateway_injection: true`; leave `session_key` empty for a local CLI session.
6. Enable `voice_enabled` and/or `control_enabled` in the plugin settings, then `/voicelink on`.

Command replies, spoken status lines and voice-turn guidance are English by default; set `locale: ko` for Korean. Spoken status lines (such as "preparing an answer") also follow `locale: ja | zh-cn | de | es | fr | pt-br | hi | id | th | vi`; the rest stays English in those languages.

## What to know before enabling it

- **Phone actions run without per-action Hermes approval.** Once `control_enabled` is on, `phone_act` and `phone_run_plan` act on the phone without Hermes' tool-approval prompt. Verified everyday actions (navigation, search, typing, selection) run directly. Other supported actions, including a payment, the final send of a message and placing a call, wait for a confirmation on the phone (text request) or an explicit spoken "allow" (voice request); silence is never approval. Transfers, trades and identity verification are left to the user.
- **Sandboxed Python runner.** During a voice turn the model can run a short Python program only after the code has been read aloud and approved by voice. It runs in a separate process with an empty environment, CPU/memory/file-size limits and a seccomp filter that denies file, network and process-creation system calls (Linux/aarch64 only). It is not a general terminal and not protection against kernel vulnerabilities.
- **Which model answers.** Voice requests always use the model of the Hermes **default profile**. The plugin never sends its own model or provider, so the API server's global route (the default profile's `model` setting) applies; change the model there with `hermes model`. Only the reasoning effort can be set from the phone. An override saved by an older version is ignored.
- **Conversation windows (runs mode).** Voice requests that follow each other share one session, so "do that again" works. After `voice_session_idle_minutes` without a request (default 10) or `voice_session_max_turns` requests (default 20), the next request starts a fresh session named `<session_id>.w<start time>`. Old screen snapshots are then no longer resent on every turn. Facts kept with Hermes memory stay available in every window, and earlier windows can be found with `session_search`.
- **Where voice data goes.** By default speech is recognised on the phone by Android's on-device recognizer, in the companion app's language, through this plugin's own transcription provider, and no audio leaves the phone. The recognised text joins your Hermes conversation and goes to the model provider you configured. The reply text is sent to the text-to-speech provider configured in Hermes (for example OpenAI TTS through the Nous Tool Gateway). Those providers may keep data or charge for use under their own terms.

## Tests

Run `hermes plugins validate` against this directory on a compatible Hermes installation. `tests/test_turn_admission.py` needs no Hermes installation and runs anywhere. Run the whole suite on Termux, not Windows, from this directory with Hermes' Python: `python -m unittest discover -s tests`. Linux-only system calls used for safe file handling are not supported by Windows Python.
