"""Open the voice bridge after the gateway loads the plugin."""


STARTUP_DELAYS = (5, 10, 20, 30, 60)


def start_bridge_after_load(ctx, link, delays=STARTUP_DELAYS, sleep=None):
    """Open the voice bridge soon after the gateway loads the plugin, not on the first turn.

    Hermes has no plugin hook for gateway start, so this uses only the plugin's own load.
    It goes through the same ``auto_start`` gate as ``pre_api_request``: nothing happens
    unless ``voice_enabled`` is set, after ``/voicelink off``, on a local CLI surface, or
    while the gateway's own API server is not answering (so a CLI process never binds).
    """
    import threading
    import time
    from .voicelink import LOCAL_SURFACES, _host_surface
    wait = sleep or time.sleep

    def run():
        for delay in delays:
            wait(delay)
            if _host_surface(ctx) in LOCAL_SURFACES or link.listening:
                return
            try:
                link.auto_start()
            except Exception:
                pass
            if link.listening:
                return

    threading.Thread(target=run, name="phone-voice-startup", daemon=True).start()

