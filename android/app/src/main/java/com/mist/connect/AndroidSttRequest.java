package com.mist.connect;

import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use request capability. Cancellation and the wrapper's final seal use
 * the SAME completion monitor. No callback, IO, wait, or lifecycle lock is
 * acquired by completion methods. This is not an action/LLM authorization.
 */
public final class AndroidSttRequest {
    private final int inputBytes;
    private final AndroidSttCompletion completion;
    private final AtomicBoolean claimed = new AtomicBoolean();
    public AndroidSttRequest(int inputBytes) {
        this.inputBytes = inputBytes;
        this.completion = new AndroidSttCompletion(inputBytes);
    }
    public void cancel() { completion.cancel(); }
    public boolean isActive() { return completion.isActive(); }
    /** Package-private: only the wrapper may consume this capability. */
    AndroidSttCompletion claim(int bytes) {
        if (!claimed.compareAndSet(false, true)) return null;
        if (bytes != inputBytes) { cancel(); return null; }
        return completion;
    }
}
