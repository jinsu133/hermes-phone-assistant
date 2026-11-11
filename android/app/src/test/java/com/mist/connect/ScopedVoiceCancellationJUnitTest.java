package com.mist.connect;

import org.junit.Test;

/** Run the existing pure-Java/source-wiring checks under the actual Gradle suite too. */
public final class ScopedVoiceCancellationJUnitTest {
    @Test public void scopedCancellationAndSourceWiring() throws Exception {
        ScopedVoiceCancellationTest.main(new String[]{"src/main/java/com/mist/connect"});
    }
}
