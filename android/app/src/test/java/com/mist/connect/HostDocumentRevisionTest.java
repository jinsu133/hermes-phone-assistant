package com.mist.connect;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostDocumentRevisionTest {
    private static final String DIGEST="5a9ff3006827920193347f605a728f1229a991533a64c3f2bb1c02b03b85ac9c";
    private HostDocumentRevision revision(String replacement, String digest) {
        return new HostDocumentRevision("owner","meeting","a".repeat(64),"b".repeat(64),"오전 9시",replacement,digest);
    }
    @Test public void matchesIndependentPythonDigestAndCompletePreview() {
        HostDocumentRevision d=revision("오전 10시",DIGEST);
        assertEquals(DIGEST,d.digest);
        assertTrue(d.prompt().contains("기존 내용: 오전 9시"));
        assertTrue(d.prompt().contains("수정 내용: 오전 10시"));
        assertTrue(d.prompt().contains("원본은 그대로"));
        assertTrue(d.prompt().contains(HostDocumentRevision.phrase()));
    }
    @Test public void changedReplacementCannotReuseDigest() {
        assertThrows(IllegalArgumentException.class,()->revision("오후 2시",DIGEST));
    }
    @Test public void controlsAndOversizeAreRejected() {
        for(String s:new String[]{"\u202e오전 10시","a".repeat(161),"오전 9시",null})
            assertThrows(IllegalArgumentException.class,()->revision(s,DIGEST));
    }
    @Test public void rawPathAndMalformedSourceDigestAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new HostDocumentRevision("owner","../file","a".repeat(64),"b".repeat(64),"a","b",DIGEST));
        assertThrows(IllegalArgumentException.class,()->new HostDocumentRevision("owner","meeting","x".repeat(64),"b".repeat(64),"a","b",DIGEST));
    }
}
