# Hermes Phone Control plugin

This is the Hermes plugin subdirectory of the phone-native assistant. It pairs with an Android companion on the same phone over authenticated loopback. Hermes tools can request phone observation and bounded actions through the companion's MCP service; the Android app retains its own permission, device-lock and confirmation gates. ADB is not the production action path.

The plugin requires Hermes and the companion on the same Android/Termux device. The default manifest configuration leaves voice and control disabled until enabled by the operator. Microphone, accessibility and optional overlay permission require explicit Android consent. The model and speech providers configured by the operator may send data off-device and may incur charges.

## Review status

This is a preview, not a claim of catalog admission. Local validation reported warnings for internal Hermes APIs and operations involving process access and Python compilation/execution. The existing local pairing flow also reads an app-exported credential bundle. The project must reconcile these details with current catalog admission rules before a listing is represented as compliant. See the repository root for the Android source and license notices.

Run `hermes plugins validate` against this directory on a compatible Hermes installation. Run device-specific tests on Termux, not Windows; Linux-only system calls used for safe file handling are not supported by Windows Python.
