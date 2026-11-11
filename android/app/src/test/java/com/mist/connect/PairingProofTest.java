package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public class PairingProofTest {
    private static final String PAIR="22222222-2222-4222-8222-222222222222";
    private static final String DEVICE="11111111-1111-4111-8111-111111111111";
    private static String repeat(char c, int n) { char[] chars=new char[n]; java.util.Arrays.fill(chars,c); return new String(chars); }
    @Test public void matchesIndependentPythonHmac() {
        assertEquals("e791efecb93c3c7a8e5c85606d7c0ccadde3dc0284f711c19dbdcc495e22d86a",
            PairingProof.sign(repeat('V',43),repeat('a',64),PAIR,1,DEVICE));
    }
    @Test public void changingScopeOrKeyChangesProof() {
        String original=PairingProof.sign(repeat('V',43),repeat('a',64),PAIR,1,DEVICE);
        assertNotEquals(original, PairingProof.sign(repeat('W',43),repeat('a',64),PAIR,1,DEVICE));
        assertNotEquals(original, PairingProof.sign(repeat('V',43),repeat('b',64),PAIR,1,DEVICE));
        assertNotEquals(original, PairingProof.sign(repeat('V',43),repeat('a',64),PAIR,2,DEVICE));
        assertNotEquals(original, PairingProof.sign(repeat('V',43),repeat('a',64),DEVICE,1,PAIR));
    }
    @Test public void malformedIdentifiersAndKeysRejected() {
        String[][] cases={{"short",repeat('a',64),PAIR,DEVICE},
            {repeat('V',43),"wrong",PAIR,DEVICE},{repeat('V',43),repeat('a',64),"1-1-1-1-1",DEVICE},
            {repeat('V',43),repeat('a',64),PAIR,""}};
        for (String[] c:cases) {
            try { PairingProof.sign(c[0],c[1],c[2],1,c[3]);fail("accepted malformed input"); }
            catch(IllegalArgumentException expected) {}
        }
        try { PairingProof.sign(repeat('V',43),repeat('a',64),PAIR,0,DEVICE);fail("zero revision accepted"); }
        catch(IllegalArgumentException expected) {}
    }
}
