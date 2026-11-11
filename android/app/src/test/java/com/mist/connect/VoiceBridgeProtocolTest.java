package com.mist.connect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VoiceBridgeProtocolTest {
    @Test
    public void wrapsMonoPcmAsLittleEndianWav() {
        byte[] wav = VoiceBridgeProtocol.toWav(new byte[] {0x01, 0x02, 0x03, 0x04});
        assertEquals(48, wav.length);
        assertEquals('R', wav[0]);
        assertEquals('I', wav[1]);
        assertEquals('F', wav[2]);
        assertEquals('F', wav[3]);
        assertEquals(40, littleInt(wav, 4));
        assertEquals('W', wav[8]);
        assertEquals('A', wav[9]);
        assertEquals('V', wav[10]);
        assertEquals('E', wav[11]);
        assertEquals(16000, littleInt(wav, 24));
        assertEquals(32000, littleInt(wav, 28));
        assertEquals(4, littleInt(wav, 40));
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04},
                java.util.Arrays.copyOfRange(wav, 44, 48));
    }

    @Test
    public void rejectsInvalidPcmAndRequestIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> VoiceBridgeProtocol.toWav(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> VoiceBridgeProtocol.toWav(new byte[] {1}));
        assertNull(VoiceBridgeProtocol.replyUrl("x&other=1"));
    }

    @Test
    public void validatesVoiceTokenAndBuildsFixedLoopbackUrl() {
        String token = "Abcdefghijklmnopqrstuvwxyz0123456789_-ABCDEFG";
        assertTrue(VoiceBridgeProtocol.validToken(token));
        assertFalse(VoiceBridgeProtocol.validToken(token + " "));
        assertFalse(VoiceBridgeProtocol.validToken("short"));
        assertEquals("http://127.0.0.1:5001/reply?request_id=Abc_123-xyz",
                VoiceBridgeProtocol.replyUrl("Abc_123-xyz"));
    }

    @Test
    public void microphoneLeaseIsExclusiveAcrossServices() {
        assertTrue(MicrophoneOwner.claim());
        assertFalse(MicrophoneOwner.claim());
        MicrophoneOwner.release();
        assertTrue(MicrophoneOwner.claim());
        MicrophoneOwner.release();
    }

    private static int littleInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }
}
