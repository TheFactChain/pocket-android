package io.github.nekontritce11123.pocketandroid.probe;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class NtpPacketSelfTest {
    public static void main(String[] args) {
        byte[] request = NtpPacket.request();
        check(request.length == 48 && request[0] == 0x23, "Not a 48-byte NTP v4 client request");
        check(ByteBuffer.wrap(request).getLong(40) != 0, "Missing request transmit timestamp");
        check(!Arrays.equals(request, NtpPacket.request()), "Reused transmit timestamp");
        byte[] reply = new byte[48];
        reply[0] = 0x24;
        reply[1] = 2;
        System.arraycopy(request, 40, reply, 24, 8);
        ByteBuffer.wrap(reply).putLong(40, 123456789L);
        check(NtpPacket.validate(reply, 0, 48, request) == 2, "Valid NTP v4 response rejected");
        reply[0] = 0x1c;
        check(NtpPacket.validate(reply, 0, 48, request) == 2, "Valid NTP v3 response rejected");
        reply[0] = 0x24;
        byte[] padded = new byte[60];
        System.arraycopy(reply, 0, padded, 4, 48);
        check(NtpPacket.validate(padded, 4, 48, request) == 2, "Packet offset ignored");
        reject(reply, 0, 47, request);
        reject(reply, -1, 48, request);
        reject(reply, 1, 48, request);
        reject(reply, 0, Integer.MAX_VALUE, request);
        reject(null, 0, 48, request);
        reject(reply, 0, 48, new byte[47]);
        malformed(reply, request, 0, 0x14); // Version 2.
        malformed(reply, request, 0, 0x23); // Echoed client packet.
        malformed(reply, request, 0, 0x25); // Broadcast mode.
        malformed(reply, request, 0, 0xe4); // Unsynchronised leap indicator.
        malformed(reply, request, 1, 0); // Kiss-of-death.
        malformed(reply, request, 1, 16);
        malformed(reply, request, 1, 255);
        byte[] stale = reply.clone();
        stale[24] ^= 1;
        reject(stale, 0, 48, request);
        byte[] zeroTransmit = reply.clone();
        Arrays.fill(zeroTransmit, 40, 48, (byte) 0);
        reject(zeroTransmit, 0, 48, request);
        reject(request, 0, 48, request);
        System.out.println("NtpPacketSelfTest PASS (request, uniqueness, v3/v4, offset, lengths, mode, stratum, correlation, transmit)");
    }

    private static void malformed(byte[] valid, byte[] request, int index, int value) {
        byte[] packet = valid.clone();
        packet[index] = (byte) value;
        reject(packet, 0, packet.length, request);
    }

    private static void reject(byte[] packet, int offset, int length, byte[] request) {
        try {
            NtpPacket.validate(packet, offset, length, request);
            throw new AssertionError("Accepted malformed or unrelated NTP response");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
