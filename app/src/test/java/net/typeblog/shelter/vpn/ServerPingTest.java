package net.typeblog.shelter.vpn;

import org.junit.Test;

import static org.junit.Assert.*;

public class ServerPingTest {
    @Test public void probeIsAQuicInitialWithReservedVersion() {
        byte[] probe = ServerPing.versionProbe();
        assertEquals(1200, probe.length);
        assertEquals(0xC0, probe[0] & 0xC0);
        assertArrayEquals(new byte[] {0x1a, 0x2a, 0x3a, 0x4a}, new byte[] {probe[1], probe[2], probe[3], probe[4]});
        assertEquals(8, probe[5]);
        assertEquals(8, probe[14]);
    }

    @Test public void onlyAVersionNegotiationForOurIdsCounts() {
        byte[] probe = ServerPing.versionProbe();
        byte[] reply = new byte[64];
        reply[0] = (byte) 0x80;
        reply[5] = 8;
        System.arraycopy(probe, 15, reply, 6, 8);
        reply[14] = 8;
        System.arraycopy(probe, 6, reply, 15, 8);
        assertTrue(ServerPing.matchesIds(probe, reply, 31));
        reply[20] ^= 1;
        assertFalse(ServerPing.matchesIds(probe, reply, 31));
        assertFalse(ServerPing.matchesIds(probe, reply, 10));
    }
}
