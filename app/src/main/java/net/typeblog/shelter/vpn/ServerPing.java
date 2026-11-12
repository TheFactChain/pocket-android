package net.typeblog.shelter.vpn;

import android.net.Network;
import android.net.VpnService;

import net.typeblog.shelter.connections.ConnectionProfile;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Round trip to each server outside the tunnel, as Happ's ping shows it: the time of a TCP handshake, or
 * for Hysteria2 (QUIC over UDP) the time of the server's QUIC version-negotiation reply.
 * shortcut: handshake time only, not a full request through the proxy; a server that answers here may
 * still refuse the connection. Upgrade to a core URL test when servers can be tested in parallel.
 */
final class ServerPing {
    static final int TIMEOUT_MS = 3000;
    /** Server reached nothing: no reply in time or the address does not resolve. */
    static final long FAILED = -1;
    /** Salamander hides QUIC from anyone without the password, so there is nothing to time. */
    static final long UNMEASURABLE = -2;

    private ServerPing() { }

    static Map<String, Long> all(VpnService vpn, Network network, List<ConnectionProfile> servers) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(8, servers.size())));
        for (ConnectionProfile server : servers) pool.execute(() -> result.put(server.id, one(vpn, network, server)));
        pool.shutdown();
        try { pool.awaitTermination(TIMEOUT_MS * 2L + 2000L * (servers.size() / 8 + 1), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        pool.shutdownNow();
        return result;
    }

    static long one(VpnService vpn, Network network, ConnectionProfile server) {
        if (server.type.equals("hysteria2") && !server.obfsPassword.isEmpty()) return UNMEASURABLE;
        InetAddress address;
        try {
            // Resolved on the real network: through the tunnel the answer and the time would be the tunnel's.
            address = network != null ? network.getByName(server.server) : InetAddress.getByName(server.server);
        } catch (IOException | RuntimeException e) {
            return FAILED;
        }
        try {
            return server.type.equals("hysteria2") ? quic(vpn, network, address, server.port) : tcp(vpn, network, address, server.port);
        } catch (IOException | RuntimeException e) {
            return FAILED;
        }
    }

    private static long tcp(VpnService vpn, Network network, InetAddress address, int port) throws IOException {
        try (Socket socket = new Socket()) {
            if (network != null) network.bindSocket(socket);
            else if (!vpn.protect(socket)) return FAILED;
            long start = System.nanoTime();
            socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);
            return Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private static long quic(VpnService vpn, Network network, InetAddress address, int port) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (network != null) network.bindSocket(socket);
            else if (!vpn.protect(socket)) return FAILED;
            socket.setSoTimeout(TIMEOUT_MS);
            byte[] probe = versionProbe();
            long start = System.nanoTime();
            socket.send(new DatagramPacket(probe, probe.length, address, port));
            byte[] reply = new byte[1500];
            DatagramPacket packet = new DatagramPacket(reply, reply.length);
            long deadline = start + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            while (true) {
                socket.receive(packet);
                // Version negotiation: long header with version 0 and the connection ids echoed back.
                if (packet.getLength() >= 7 && (reply[0] & 0x80) != 0 && reply[1] == 0 && reply[2] == 0 && reply[3] == 0 && reply[4] == 0
                        && matchesIds(probe, reply, packet.getLength())) {
                    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) return FAILED;
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
        }
    }

    /** A 1200-byte QUIC Initial with a reserved version: every QUIC server must answer with its versions. */
    static byte[] versionProbe() {
        byte[] packet = new byte[1200];
        SecureRandom random = new SecureRandom();
        random.nextBytes(packet);
        packet[0] = (byte) (0xC0 | (packet[0] & 0x0F));
        // 0x?a?a?a?a versions are reserved to force negotiation (RFC 9000, section 15).
        packet[1] = 0x1a; packet[2] = 0x2a; packet[3] = 0x3a; packet[4] = 0x4a;
        packet[5] = 8; // destination connection id, 8 random bytes follow
        packet[14] = 8; // source connection id, 8 random bytes follow
        return packet;
    }

    /** The reply's destination id is our source id and its source id is our destination id. */
    static boolean matchesIds(byte[] probe, byte[] reply, int length) {
        if (length < 6 + 8 + 1 + 8 || reply[5] != 8) return false;
        for (int i = 0; i < 8; i++) if (reply[6 + i] != probe[15 + i]) return false;
        if (reply[14] != 8) return false;
        for (int i = 0; i < 8; i++) if (reply[15 + i] != probe[6 + i]) return false;
        return true;
    }
}
