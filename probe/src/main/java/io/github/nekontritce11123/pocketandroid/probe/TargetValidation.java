package io.github.nekontritce11123.pocketandroid.probe;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import java.util.Properties;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

public final class TargetValidation {
    private TargetValidation() { }

    public static Properties readTarget(File file) {
        Properties target = new Properties();
        if (!file.exists()) return target;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[4097];
            int size = 0, read;
            while (size < bytes.length && (read = input.read(bytes, size, bytes.length - size)) != -1) size += read;
            if (size > 4096) throw new IllegalArgumentException();
            target.load(new ByteArrayInputStream(bytes, 0, size));
            for (String key : target.stringPropertyNames()) {
                if (!Arrays.asList("url", "resolveHost", "interval", "udpHost", "udpPort",
                        "repeat", "durationMinutes", "leakUrl", "salt").contains(key))
                    throw new IllegalArgumentException();
            }
            if (target.containsKey("repeat")) bool(target.getProperty("repeat"));
            if (target.containsKey("durationMinutes")) minutes(Integer.parseInt(target.getProperty("durationMinutes")));
            if (target.containsKey("leakUrl")) leakUrl(target.getProperty("leakUrl"));
            if (target.containsKey("salt")) salt(target.getProperty("salt"));
            if (target.containsKey("url")) url(target.getProperty("url"));
            if (target.containsKey("resolveHost")) host(target.getProperty("resolveHost"));
            if (target.containsKey("interval")) interval(Integer.parseInt(target.getProperty("interval")));
            if (target.containsKey("udpHost") != target.containsKey("udpPort")) throw new IllegalArgumentException();
            if (target.containsKey("udpHost")) {
                host(target.getProperty("udpHost"));
                port(Integer.parseInt(target.getProperty("udpPort")));
            }
            return target;
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный приватный файл цели: лимит 4 КиБ, поля url/resolveHost/interval/udpHost/udpPort.");
        }
    }

    public static URL url(String text) {
        try {
            if (text == null || text.length() > 2048) throw new IllegalArgumentException();
            URI uri = new URI(text);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IllegalArgumentException();
            }
            return uri.toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Нужен HTTP(S) URL без учётных данных и фрагмента, с портом 1–65535.");
        }
    }

    public static String host(String text) {
        try {
            if (text == null || text.isEmpty() || text.length() > 253 || text.contains("%")) {
                throw new IllegalArgumentException();
            }
            if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
            if (text.contains(":")) {
                URI ipv6 = new URI("http://[" + text + "]/");
                if (ipv6.getHost() == null) throw new IllegalArgumentException();
                return text;
            }
            String ascii = IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES);
            if (ascii.isEmpty() || ascii.equals(".") || ascii.length() > 253) throw new IllegalArgumentException();
            return ascii;
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new IllegalArgumentException("Некорректное имя узла DNS/UDP.");
        }
    }

    public static int port(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("UDP-порт должен быть от 1 до 65535.");
        return port;
    }

    public static int interval(int seconds) {
        if (seconds < 1 || seconds > 60) throw new IllegalArgumentException("Интервал должен быть от 1 до 60 секунд.");
        return seconds;
    }

    public static boolean bool(String text) {
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw new IllegalArgumentException("Ожидается true или false.");
    }

    /** Stand runs need longer series than a manual check, but still a hard upper bound. */
    public static int minutes(int minutes) {
        if (minutes < 1 || minutes > ProbeRunner.MAX_RUN_MILLIS / 60000) throw new IllegalArgumentException("Длительность от 1 до 30 минут.");
        return minutes;
    }

    /** The leak detector is a plain-HTTP marker on the PC observer; anything else is a mistake. */
    public static URL leakUrl(String text) {
        URL url = url(text);
        if (!url.getProtocol().equals("http") || !url.getPath().startsWith("/pocket-test/") || url.getQuery() != null) {
            throw new IllegalArgumentException("leakUrl: нужен http://<наблюдатель>/pocket-test/<run> без параметров.");
        }
        return url;
    }

    public static String salt(String text) {
        if (text == null || !text.matches("[0-9a-f]{16,64}")) throw new IllegalArgumentException("salt: 16–64 шестнадцатеричных символа.");
        return text;
    }
}
