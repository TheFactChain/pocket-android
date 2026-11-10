package net.typeblog.shelter.connections;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Validated connection data. Secrets are included only by explicit JSON serialization. */
public final class ConnectionProfile {
    public final String id, name, type, server;
    public final int port;
    public final String password, uuid, serverName, realityPublicKey, shortId, fingerprint, flow,
            obfsPassword, username;
    /** Types offered in the interface, as in Pocket Windows; VLESS stays readable by code only. */
    public static final java.util.List<String> UI_TYPES = Arrays.asList("hysteria2", "socks5", "http", "https");

    private ConnectionProfile(JSONObject json) {
        requireKeys(json, "id", "name", "type", "server", "port", "password", "uuid",
                "serverName", "realityPublicKey", "shortId", "fingerprint", "flow", "obfsPassword", "username");
        id =string(json, "id", UUID.randomUUID().toString());
        if (id.isEmpty() || id.length() > 128) throw invalid("Некорректный идентификатор подключения.");
        type = string(json, "type", "");
        if (!UI_TYPES.contains(type) && !type.equals("vless")) {
            throw invalid("Поддерживаются Hysteria2, SOCKS5, HTTP и HTTPS.");
        }
        server = host(string(json, "server", ""));
        Object portValue = json.opt("port");
        if (!(portValue instanceof Number)) throw invalid("Порт должен быть целым числом от 1 до 65535.");
        double number = ((Number) portValue).doubleValue();
        if (number < 1 || number > 65535 || number != Math.floor(number)) {
            throw invalid("Порт должен быть целым числом от 1 до 65535.");
        }
        port = (int) number;
        name = string(json, "name", server + ":" + port);
        if (name.isEmpty() || name.length() > 256) throw invalid("Некорректное название подключения.");
        password = string(json, "password", "");
        uuid = string(json, "uuid", "");
        realityPublicKey = string(json, "realityPublicKey", "");
        shortId = string(json, "shortId", "").toLowerCase(Locale.ROOT);
        flow = string(json, "flow", "");
        obfsPassword = string(json, "obfsPassword", "");
        username = string(json, "username", "");
        String sni = string(json, "serverName", "");
        if (!type.equals("vless") && (!uuid.isEmpty() || !realityPublicKey.isEmpty() || !shortId.isEmpty()
                || !flow.isEmpty() || !string(json, "fingerprint", "").isEmpty())) {
            throw invalid("Параметры VLESS нельзя применять к этому типу.");
        }
        if (!type.equals("hysteria2") && !obfsPassword.isEmpty()) {
            throw invalid("Маскировка Salamander есть только у Hysteria2.");
        }
        if (isProxy()) {
            if (username.isEmpty() != password.isEmpty()) throw invalid("Укажи и логин, и пароль, или ни одного.");
            if (!type.equals("https") && !sni.isEmpty()) throw invalid("Имя TLS есть только у HTTPS и Hysteria2.");
            serverName = type.equals("https") ? host(sni.isEmpty() ? server : sni) : "";
            fingerprint = "";
        } else if (!username.isEmpty()) {
            throw invalid("Логин есть только у SOCKS5, HTTP и HTTPS.");
        } else if (type.equals("hysteria2")) {
            if (password.isEmpty()) throw invalid("Для Hysteria2 требуется пароль.");
            serverName = host(sni.isEmpty() ? server : sni);
            fingerprint = "";
        } else {
            if (!password.isEmpty()) throw invalid("Параметры Hysteria2 нельзя применять к VLESS.");
            if (!uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw invalid("Для VLESS требуется корректный UUID.");
            }
            if (!validPublicKey(realityPublicKey)) throw invalid("Некорректный публичный ключ REALITY.");
            if (!shortId.matches("(?:[0-9a-f]{2}){0,8}")) throw invalid("Некорректный short ID REALITY.");
            if (!flow.isEmpty() && !flow.equals("xtls-rprx-vision")) throw invalid("Неподдерживаемый flow VLESS.");
            if (sni.isEmpty()) throw invalid("Для REALITY требуется имя сервера TLS (SNI).");
            serverName = host(sni);
            String fp = string(json, "fingerprint", "");
            fingerprint = fp.isEmpty() ? "chrome" : fp;
            if (!Arrays.asList("chrome", "firefox", "edge", "safari", "360", "qq", "ios",
                    "android", "random", "randomized").contains(fingerprint)) {
                throw invalid("Неподдерживаемый отпечаток TLS.");
            }
        }
    }

    public static ConnectionProfile fromJson(JSONObject json) {
        if (json == null) throw invalid("Не задано подключение.");
        return new ConnectionProfile(json);
    }

    public JSONObject toJson() {
        try {
            return new JSONObject().put("id", id).put("name", name).put("type", type)
                    .put("server", server).put("port", port).put("password", password)
                    .put("uuid", uuid).put("serverName", serverName).put("realityPublicKey", realityPublicKey)
                    .put("shortId", shortId).put("fingerprint", fingerprint).put("flow", flow)
                    .put("obfsPassword", obfsPassword).put("username", username);
        } catch (JSONException e) {
            throw invalid("Не удалось сериализовать подключение.");
        }
    }

    /** The file Pocket Windows exports and imports (connection_profile.py schema 1). */
    public JSONObject toWindowsJson(boolean includeSecrets) {
        if (!UI_TYPES.contains(type)) throw invalid("VLESS нельзя экспортировать в формат Pocket Windows.");
        try {
            boolean tls = type.equals("hysteria2") || type.equals("https");
            JSONObject result = new JSONObject().put("schema", 1).put("name", name).put("protocol", type)
                    .put("host", server).put("port", port).put("tls_verify", tls).put("udp_supported", !isProxy());
            if (tls && !serverName.equals(server)) result.put("tls_server_name", serverName);
            if (!obfsPassword.isEmpty()) result.put("obfs", "salamander");
            if (includeSecrets) {
                JSONObject credentials = new JSONObject();
                if (type.equals("hysteria2")) {
                    credentials.put("auth", password);
                    if (!obfsPassword.isEmpty()) credentials.put("obfs_password", obfsPassword);
                } else if (!username.isEmpty()) {
                    credentials.put("username", username).put("password", password);
                }
                result.put("credentials", credentials);
            }
            return result;
        } catch (JSONException e) {
            throw invalid("Не удалось подготовить экспорт подключения.");
        }
    }

    public JSONObject toOutbound() {
        try {
            JSONObject tls = new JSONObject().put("enabled", true).put("server_name", serverName);
            JSONObject outbound = new JSONObject().put("tag", "proxy").put("server", server).put("server_port", port);
            if (isProxy()) {
                // Same mapping as the Windows gateway: https is an HTTP CONNECT proxy over TLS.
                outbound.put("type", type.equals("socks5") ? "socks" : "http");
                if (type.equals("socks5")) outbound.put("version", "5").put("network", "tcp");
                if (type.equals("https")) outbound.put("tls", tls.put("insecure", false));
                if (!username.isEmpty()) outbound.put("username", username).put("password", password);
                return outbound;
            }
            outbound.put("type", type).put("tls", tls);
            if (type.equals("hysteria2")) {
                outbound.put("password", password);
                if (!obfsPassword.isEmpty()) {
                    outbound.put("obfs", new JSONObject().put("type", "salamander").put("password", obfsPassword));
                }
            } else {
                outbound.put("uuid", uuid).put("packet_encoding", "xudp");
                if (!flow.isEmpty()) outbound.put("flow", flow);
                tls.put("utls", new JSONObject().put("enabled", true).put("fingerprint", fingerprint));
                tls.put("reality", new JSONObject().put("enabled", true)
                        .put("public_key", realityPublicKey).put("short_id", shortId));
            }
            return outbound;
        } catch (JSONException e) {
            throw invalid("Не удалось создать конфигурацию подключения.");
        }
    }

    /** SOCKS5, HTTP and HTTPS carry TCP only; UDP is refused for them, as in Pocket Windows. */
    public boolean isProxy() {
        return type.equals("socks5") || type.equals("http") || type.equals("https");
    }

    public boolean udpSupported() {
        return !isProxy();
    }

    /** Interface name of the type, as Pocket Windows shows it. */
    public String typeLabel() {
        switch (type) {
            case "hysteria2": return "Hysteria2";
            case "socks5": return "SOCKS5";
            case "http": return "HTTP";
            case "https": return "HTTPS";
            default: return "VLESS";
        }
    }

    static String string(JSONObject json, String key, String fallback) {
        if (!json.has(key)) return fallback;
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid("Неверный тип поля подключения.");
        String text = (String) value;
        if (text.length() > 4096) throw invalid("Слишком длинное поле подключения.");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) throw invalid("Недопустимый символ в поле подключения.");
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw invalid("Некорректный текст подключения.");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw invalid("Некорректный текст подключения.");
            }
        }
        return text;
    }

    static void requireKeys(JSONObject json, String... allowed) {
        Set<String> keys = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) {
            if (!keys.contains(iterator.next())) throw invalid("Неподдерживаемые поля подключения.");
        }
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static boolean validPublicKey(String key) {
        if (!key.matches("[A-Za-z0-9_-]{43}=?")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(key);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
                    .equals(key.replace("=", ""));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String host(String text) {
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length() - 1);
        if (text.isEmpty() || text.length() > 253 || text.indexOf('%') >= 0) {
            throw invalid("Некорректный адрес сервера.");
        }
        if (text.contains(":")) {
            try {
                URI uri = new URI("tcp://[" + text + "]:443");
                if (uri.getHost() == null) throw invalid("Некорректный IPv6-адрес сервера.");
                return text.toLowerCase(Locale.ROOT);
            } catch (URISyntaxException e) {
                throw invalid("Некорректный IPv6-адрес сервера.");
            }
        }
        try {
            String ascii = IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.isEmpty() || ascii.length() > 253) throw invalid("Некорректный адрес сервера.");
            if (ascii.matches("[0-9.]+")) {
                String[] parts = ascii.split("\\.", -1);
                if (parts.length != 4) throw invalid("Некорректный IPv4-адрес сервера.");
                for (String part : parts) {
                    if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) {
                        throw invalid("Некорректный IPv4-адрес сервера.");
                    }
                }
            }
            return ascii;
        } catch (IllegalArgumentException e) {
            throw invalid("Некорректный адрес сервера.");
        }
    }
}
