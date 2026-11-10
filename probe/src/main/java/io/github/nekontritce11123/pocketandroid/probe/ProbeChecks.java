package io.github.nekontritce11123.pocketandroid.probe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

final class ProbeChecks implements ProbeRunner.Check {
    static final String IP_URL = "https://api.ipify.org";
    private static final int MAX_RESPONSE_BYTES = 4096;
    private final URL url;
    private final String dns, udp, identity;
    private final int port, uid;
    private final boolean ipOnly;
    private final Consumer<String> logger;
    private int sequence;

    ProbeChecks(URL url, String dns, String udp, int port, boolean ipOnly,
                int uid, String identity, Consumer<String> logger) {
        this.url = url;
        this.dns = dns;
        this.udp = udp;
        this.port = port;
        this.ipOnly = ipOnly;
        this.uid = uid;
        this.identity = identity;
        this.logger = logger;
    }

    @Override public String run(ProbeRunner.Session session) {
        if (session.cancelled()) return "Проверка отменена.";
        String marker = "uid=" + uid + " run=" + session.id + " seq=" + (++sequence);
        StringBuilder output = new StringBuilder(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date())).append('\n').append(identity)
                .append("\nСерия: ").append(session.id).append("  Запрос: ").append(sequence)
                .append("\nURL: ").append(url).append('\n');
        output.append(http(session, marker));
        if (session.cancelled()) return output.toString();
        if (!ipOnly) {
            try {
                logger.accept(marker + " DNS START");
                InetAddress[] addresses = InetAddress.getAllByName(dns);
                if (session.cancelled()) return output.toString();
                output.append("\nDNS ").append(dns).append(':');
                for (int i = 0; i < Math.min(addresses.length, 16); i++) output.append(' ').append(addresses[i].getHostAddress());
                if (addresses.length > 16) output.append(" (первые 16 адресов)");
                logger.accept(marker + " DNS OK addresses=" + addresses.length);
            } catch (Exception e) {
                output.append("\nDNS: ошибка (").append(e.getClass().getSimpleName()).append(')');
                if (!session.cancelled()) logger.accept(marker + " DNS " + e.getClass().getSimpleName());
            }
            if (session.cancelled()) return output.toString();
            if (udp != null) output.append('\n').append(udp(session, marker));
            else output.append("\nUDP: не задан; запрос не отправлялся.");
        }
        return output.toString();
    }

    private String http(ProbeRunner.Session session, String marker) {
        HttpURLConnection connection = null;
        try {
            if (session.cancelled()) return "HTTP: отменено";
            connection = (HttpURLConnection) url.openConnection();
            if (!session.register(connection::disconnect)) return "HTTP: отменено";
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "PocketProbe/1.2 " + marker);
            connection.setRequestProperty("Accept", "text/plain");
            if (session.cancelled()) return "HTTP: отменено";
            logger.accept(marker + " HTTP START");
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] buffer = new byte[512];
                    while (body.size() < MAX_RESPONSE_BYTES && !session.cancelled()) {
                        int count = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES - body.size()));
                        if (count < 0) break;
                        body.write(buffer, 0, count);
                    }
                }
            }
            if (!session.cancelled()) logger.accept(marker + " HTTP status=" + status + " bytes=" + body.size());
            return "HTTP: " + status + "\n" + new String(body.toByteArray(), StandardCharsets.UTF_8)
                    + (body.size() == MAX_RESPONSE_BYTES ? "\n[достигнут лимит 4096 байт]" : "");
        } catch (Exception e) {
            if (!session.cancelled()) logger.accept(marker + " HTTP " + e.getClass().getSimpleName());
            return "HTTP: ошибка (" + e.getClass().getSimpleName() + ")";
        } finally {
            if (connection != null) connection.disconnect();
            session.clearAbort();
        }
    }

    private String udp(ProbeRunner.Session session, String marker) {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!session.register(socket::close)) return "UDP: отменено";
            socket.setSoTimeout(3000);
            InetAddress address = InetAddress.getByName(udp);
            if (session.cancelled()) return "UDP: отменено";
            socket.connect(address, port);
            byte[] bytes = port == 123 ? NtpPacket.request()
                    : ("PocketProbe uid=" + uid + " time=" + System.currentTimeMillis() + " token=" + sequence)
                    .getBytes(StandardCharsets.UTF_8);
            if (session.cancelled()) return "UDP: отменено";
            logger.accept(marker + " UDP START");
            socket.send(new DatagramPacket(bytes, bytes.length));
            DatagramPacket reply = new DatagramPacket(new byte[MAX_RESPONSE_BYTES], MAX_RESPONSE_BYTES);
            socket.receive(reply);
            if (session.cancelled()) return "UDP: отменено";
            if (port == 123) {
                try {
                    int stratum = NtpPacket.validate(reply.getData(), reply.getOffset(), reply.getLength(), bytes);
                    logger.accept(marker + " UDP OK NTP stratum=" + stratum + " bytes=" + reply.getLength());
                    return "UDP NTP " + udp + ":" + port + ": ответ сервера подтверждён, stratum="
                            + stratum + ", " + reply.getLength() + " байт";
                } catch (IllegalArgumentException e) {
                    logger.accept(marker + " UDP NTP INVALID");
                    return "UDP NTP: некорректный ответ (" + e.getMessage() + ")";
                }
            }
            if (!session.cancelled()) logger.accept(marker + " UDP OK bytes=" + reply.getLength());
            return "UDP " + udp + ":" + port + ": получено " + reply.getLength() + " байт\n"
                    + new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (!session.cancelled()) logger.accept(marker + " UDP " + e.getClass().getSimpleName());
            return "UDP: ошибка (" + e.getClass().getSimpleName() + ")";
        } finally {
            session.clearAbort();
        }
    }
}
