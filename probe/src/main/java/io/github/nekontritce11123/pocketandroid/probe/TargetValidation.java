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
                if (!Arrays.asList("url", "resolveHost", "interval", "udpHost", "udpPort").contains(key))
                    throw new IllegalArgumentException();
            }
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
}
