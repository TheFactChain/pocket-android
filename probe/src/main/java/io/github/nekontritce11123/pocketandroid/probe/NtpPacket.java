package io.github.nekontritce11123.pocketandroid.probe;

import java.nio.ByteBuffer;

final class NtpPacket {
    private static long lastTimestamp = Long.MIN_VALUE;

    private NtpPacket() { }

    static synchronized byte[] request() {
        long millis = System.currentTimeMillis();
        long timestamp = ((millis / 1000 + 2208988800L) << 32)
                | ((millis % 1000) * 0x100000000L / 1000);
        if (timestamp <= lastTimestamp) timestamp = lastTimestamp + 1;
        lastTimestamp = timestamp;
        byte[] packet = new byte[48];
        packet[0] = 0x23; // NTP version 4, client mode.
        ByteBuffer.wrap(packet).putLong(40, timestamp);
        return packet;
    }

    static int validate(byte[] response, int offset, int length, byte[] request) {
        if (response == null || offset < 0 || length < 48 || offset > response.length - length
                || request == null || request.length != 48) {
            throw new IllegalArgumentException("нужен NTP-пакет не короче 48 байт");
        }
        int flags = response[offset] & 0xff;
        int version = (flags >> 3) & 7;
        if (version != 3 && version != 4) throw new IllegalArgumentException("неподдерживаемая версия NTP");
        if ((flags & 7) != 4) throw new IllegalArgumentException("ответ не в режиме NTP server");
        int stratum = response[offset + 1] & 0xff;
        if ((flags >> 6) == 3 || stratum < 1 || stratum > 15) {
            throw new IllegalArgumentException("сервер не синхронизирован или отказал в запросе");
        }
        for (int i = 0; i < 8; i++) {
            if (response[offset + 24 + i] != request[40 + i]) {
                throw new IllegalArgumentException("originate timestamp не совпадает с запросом");
            }
        }
        if (ByteBuffer.wrap(response).getLong(offset + 40) == 0) {
            throw new IllegalArgumentException("нулевой transmit timestamp сервера");
        }
        return stratum;
    }
}
