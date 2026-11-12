package net.typeblog.shelter.connections;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.typeblog.shelter.connections.ConnectionProfile.invalid;
import static net.typeblog.shelter.connections.ConnectionProfile.requireKeys;
import static net.typeblog.shelter.connections.ConnectionProfile.string;

/** Imports connection parameters only. Foreign routing, DNS and executable config are never retained. */
public final class ProfileImporter {
    public static final int MAX_INPUT_BYTES = 1048576;
    private static final int MAX_RECORDS = 256;
    private static final int MAX_DEPTH = 32;

    private ProfileImporter() { }

    public static final class Result {
        public final List<ConnectionProfile> profiles;
        public final List<String> warnings;
        /** A Pocket Windows file exported without passwords: flat fields to finish in the editor. */
        public final JSONObject draft;

        private Result(List<ConnectionProfile> profiles, List<String> warnings, JSONObject draft) {
            this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
            this.draft = draft;
        }
    }

    /** What the interface accepts; a Pocket Windows file without passwords becomes a draft for the editor. */
    public static Result parseForInterface(String input) {
        try {
            return parse(input);
        } catch (NeedsCredentials e) {
            return new Result(Collections.<ConnectionProfile>emptyList(), Collections.<String>emptyList(), e.draft);
        }
    }

    /** A subscription body is often one base64 block of links; anything else is returned unchanged. */
    public static String decodeBase64List(String text) {
        String compact = text.replaceAll("\\s+", "").replace("=", "");
        if (compact.isEmpty() || text.contains("://") || !compact.matches("[A-Za-z0-9+/_-]+")) return text;
        try {
            boolean url = compact.indexOf('-') >= 0 || compact.indexOf('_') >= 0;
            // Java's decoder does not require the padding that subscription panels often drop.
            byte[] bytes = (url ? java.util.Base64.getUrlDecoder() : java.util.Base64.getDecoder()).decode(compact);
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return decoded.contains("://") ? decoded : text;
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return text;
        }
    }

    static final class NeedsCredentials extends IllegalArgumentException {
        final JSONObject draft;

        NeedsCredentials(JSONObject draft) {
            super("В файле нет пароля. Введи его, чтобы сохранить подключение.");
            this.draft = draft;
        }
    }

    public static Result parse(String input) {
        if (input == null || input.length() > MAX_INPUT_BYTES) throw invalid("Нет данных или превышен лимит импорта 1 МиБ.");
        try {
            int bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(input)).remaining();
            if (bytes > MAX_INPUT_BYTES) throw invalid("Превышен лимит импорта 1 МиБ.");
        } catch (CharacterCodingException e) {
            throw invalid("Ввод содержит некорректный текст UTF-8.");
        }
        String text = input.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        text = decodeBase64List(text).trim();
        if (text.isEmpty()) throw invalid("Нет данных для импорта.");
        List<ConnectionProfile> profiles = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (text.startsWith("{") || text.startsWith("[")) {
            parseJson(text, profiles, warnings);
        } else {
            try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
                String line;
                int record = 0;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                    if (++record > MAX_RECORDS) throw invalid("Слишком много записей: допускается не более 256.");
                    try {
                        profiles.add(parseUri(line));
                    } catch (IllegalArgumentException e) {
                        warnings.add("Запись " + record + " пропущена: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                throw invalid("Не удалось прочитать список подключений.");
            }
        }
        if (profiles.isEmpty()) {
            throw invalid(warnings.isEmpty()
                    ? "Не найдено пригодных подключений. Нужна ссылка hysteria2://, socks5://, http:// или https://, или файл подключения."
                    : warnings.get(0));
        }
        return new Result(profiles, warnings, null);
    }

    private static void parseJson(String text, List<ConnectionProfile> profiles, List<String> warnings) {
        checkDepth(text);
        try {
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (tokener.nextClean() != 0) throw invalid("Ожидается один полный JSON-объект.");
            // Happ «JSON» subscriptions: a list of full Xray configs, one server each.
            if (value instanceof JSONArray) { XrayImport.parseList((JSONArray) value, profiles, warnings); return; }
            if (!(value instanceof JSONObject)) throw invalid("Ожидается один полный JSON-объект.");
            JSONObject json = (JSONObject) value;
            if (XrayImport.isXrayConfig(json)) {
                XrayImport.parseList(new JSONArray().put(json), profiles, warnings);
                return;
            }
            if (json.has("outbounds")) {
                Object records = json.opt("outbounds");
                if (!(records instanceof JSONArray)) throw invalid("Поле outbounds должно содержать список подключений.");
                JSONArray array = (JSONArray) records;
                if (array.length() > MAX_RECORDS) throw invalid("Слишком много записей: допускается не более 256.");
                if (json.length() > 1) {
                    warnings.add("Другие разделы конфигурации, включая route и dns, не импортируются. Pocket использует собственные правила.");
                }
                for (int i = 0; i < array.length(); i++) {
                    Object record = array.opt(i);
                    try {
                        if (!(record instanceof JSONObject)) throw invalid("Ожидается объект подключения.");
                        profiles.add(parseRecord((JSONObject) record));
                    } catch (IllegalArgumentException e) {
                        warnings.add("Запись " + (i + 1) + " пропущена: " + e.getMessage());
                    }
                }
            } else {
                profiles.add(parseRecord(json));
            }
        } catch (JSONException e) {
            throw invalid("Некорректный JSON подключения.");
        }
    }

    private static ConnectionProfile parseRecord(JSONObject json) {
        if (json.has("protocol")) return ConnectionProfile.fromJson(fromWindows(json));
        if (json.has("port")) {
            // Imported records must never replace an existing, possibly selected connection.
            json.remove("id");
            json.remove("subscription");
            return ConnectionProfile.fromJson(json);
        }
        String type = string(json, "type", "");
        if (!type.equals("hysteria2") && !type.equals("vless")) {
            throw invalid("Тип подключения не поддерживается.");
        }
        if (type.equals("hysteria2")) {
            requireKeys(json, "type", "tag", "server", "server_port", "password", "tls", "obfs");
        } else {
            requireKeys(json, "type", "tag", "server", "server_port", "uuid", "flow", "tls", "transport", "packet_encoding");
            if (!string(json, "packet_encoding", "xudp").equals("xudp")) {
                throw invalid("Поддерживается только кодирование UDP XUDP для VLESS.");
            }
        }
        JSONObject tls = object(json, "tls");
        requireKeys(tls, "enabled", "server_name", "insecure", "utls", "reality");
        requireTrue(tls, "enabled");
        if (tls.has("insecure") && !Boolean.FALSE.equals(tls.opt("insecure"))) {
            throw invalid("Отключение проверки TLS запрещено.");
        }
        try {
            JSONObject flat = new JSONObject().put("type", type).put("server", string(json, "server", ""))
                    .put("port", json.opt("server_port")).put("serverName", string(tls, "server_name", ""));
            String tag = string(json, "tag", "");
            if (!tag.isEmpty()) flat.put("name", tag);
            if (type.equals("hysteria2")) {
                if (tls.has("utls") || tls.has("reality")) throw invalid("Неподдерживаемые параметры TLS для Hysteria 2.");
                flat.put("password", string(json, "password", ""));
                if (json.has("obfs")) {
                    JSONObject obfs = object(json, "obfs");
                    requireKeys(obfs, "type", "password");
                    if (!string(obfs, "type", "").equals("salamander")) throw invalid("Поддерживается только обфускация salamander.");
                    String password = string(obfs, "password", "");
                    if (password.isEmpty()) throw invalid("Требуется пароль обфускации salamander.");
                    flat.put("obfsPassword", password);
                }
            } else {
                flat.put("uuid", string(json, "uuid", "")).put("flow", string(json, "flow", ""));
                if (tls.has("reality")) {
                    JSONObject reality = object(tls, "reality");
                    requireKeys(reality, "enabled", "public_key", "short_id");
                    requireTrue(reality, "enabled");
                    flat.put("security", "reality").put("realityPublicKey", string(reality, "public_key", ""))
                            .put("shortId", string(reality, "short_id", ""));
                } else flat.put("security", "tls");
                if (json.has("transport")) {
                    JSONObject transport = object(json, "transport");
                    String kind = string(transport, "type", "");
                    if (kind.equals("grpc")) {
                        requireKeys(transport, "type", "service_name");
                        flat.put("transport", "grpc").put("path", string(transport, "service_name", ""));
                    } else if (kind.equals("ws")) {
                        requireKeys(transport, "type", "path", "headers");
                        flat.put("transport", "ws").put("path", string(transport, "path", ""));
                        if (transport.has("headers")) {
                            JSONObject headers = object(transport, "headers");
                            requireKeys(headers, "Host");
                            flat.put("host", string(headers, "Host", ""));
                        }
                    } else throw invalid("Для VLESS поддерживаются транспорты TCP, gRPC и WebSocket.");
                }
                if (tls.has("utls")) {
                    JSONObject utls = object(tls, "utls");
                    requireKeys(utls, "enabled", "fingerprint");
                    requireTrue(utls, "enabled");
                    flat.put("fingerprint", string(utls, "fingerprint", ""));
                }
            }
            return ConnectionProfile.fromJson(flat);
        } catch (JSONException e) {
            throw invalid("Некорректные поля подключения.");
        }
    }

    /** Pocket Windows profile file (scripts/connection_profile.py, schema 1) to the flat Android fields. */
    static JSONObject fromWindows(JSONObject json) {
        requireKeys(json, "schema", "name", "protocol", "host", "port", "tls_server_name", "obfs",
                "credentials", "tls_verify", "udp_supported");
        if (json.has("schema") && !Integer.valueOf(1).equals(json.opt("schema"))) throw invalid("Неподдерживаемая версия файла подключения.");
        String protocol = string(json, "protocol", "");
        if (!ConnectionProfile.UI_TYPES.contains(protocol)) throw invalid("Тип подключения не поддерживается.");
        boolean tls = protocol.equals("hysteria2") || protocol.equals("https");
        boolean udp = protocol.equals("hysteria2");
        if (json.has("tls_verify") && !Boolean.valueOf(tls).equals(json.opt("tls_verify"))) {
            throw invalid("Отключение проверки TLS запрещено.");
        }
        if (json.has("udp_supported") && !Boolean.valueOf(udp).equals(json.opt("udp_supported"))) {
            throw invalid("Поддержка UDP определяется типом подключения.");
        }
        String obfs = string(json, "obfs", "");
        if (!obfs.isEmpty() && (!udp || !obfs.equals("salamander"))) throw invalid("Поддерживается только маскировка Salamander у Hysteria2.");
        try {
            JSONObject flat = new JSONObject().put("type", protocol).put("server", string(json, "host", ""))
                    .put("port", json.opt("port")).put("serverName", string(json, "tls_server_name", ""));
            if (json.has("name")) flat.put("name", string(json, "name", ""));
            JSONObject credentials = json.has("credentials") ? object(json, "credentials") : new JSONObject();
            if (udp) {
                requireKeys(credentials, "auth", "obfs_password");
                flat.put("password", string(credentials, "auth", ""));
                flat.put("obfsPassword", string(credentials, "obfs_password", ""));
                if (!obfs.isEmpty() && flat.getString("obfsPassword").isEmpty() && !flat.getString("password").isEmpty()) {
                    throw invalid("Нужен пароль маскировки Salamander.");
                }
                if (obfs.isEmpty() && !flat.getString("obfsPassword").isEmpty()) throw invalid("Пароль маскировки требует Salamander.");
                if (flat.getString("password").isEmpty()) {
                    // The editor needs to know that obfuscation was on even without its password.
                    throw new NeedsCredentials(flat.put("obfs", obfs));
                }
            } else {
                requireKeys(credentials, "username", "password");
                flat.put("username", string(credentials, "username", "")).put("password", string(credentials, "password", ""));
            }
            return flat;
        } catch (JSONException e) {
            throw invalid("Некорректные поля подключения.");
        }
    }

    private static ConnectionProfile parseUri(String text) {
        try {
            // Panels often leave spaces unescaped in the server name after '#'.
            URI uri = new URI(text.replace(" ", "%20"));
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean hy2 = scheme.equals("hy2") || scheme.equals("hysteria2");
            boolean proxy = scheme.equals("socks5") || scheme.equals("http") || scheme.equals("https");
            if (!hy2 && !proxy && !scheme.equals("vless")) throw invalid("Тип ссылки не поддерживается.");
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) {
                throw invalid("Путь в ссылке подключения не поддерживается.");
            }
            String authority = uri.getRawAuthority();
            if (authority == null || authority.indexOf('@') != authority.lastIndexOf('@')
                    || (!proxy && authority.indexOf('@') <= 0)) {
                throw invalid("В ссылке должны быть учётные данные и адрес сервера.");
            }
            int at = authority.indexOf('@');
            String credential = at < 0 ? "" : authority.substring(0, at);
            String endpoint = authority.substring(at + 1);
            if (proxy) return parseProxyUri(scheme, credential, endpoint, uri);
            credential = decode(credential);
            String server;
            String port = "443";
            if (endpoint.startsWith("[")) {
                int bracket = endpoint.indexOf(']');
                if (bracket < 0) throw invalid("Некорректный IPv6-адрес сервера.");
                server = endpoint.substring(1, bracket);
                if (endpoint.length() > bracket + 1) {
                    if (endpoint.charAt(bracket + 1) != ':') throw invalid("Некорректный адрес сервера.");
                    port = endpoint.substring(bracket + 2);
                }
            } else {
                int colon = endpoint.lastIndexOf(':');
                if (colon >= 0) {
                    if (colon != endpoint.indexOf(':')) throw invalid("IPv6-адрес должен быть заключён в квадратные скобки.");
                    server = endpoint.substring(0, colon);
                    port = endpoint.substring(colon + 1);
                } else server = endpoint;
            }
            if (!port.matches("[0-9]{1,5}")) throw invalid("Некорректный порт подключения.");
            Map<String, String> query = query(uri.getRawQuery());
            List<String> allowed = hy2 ? Arrays.asList("sni", "insecure", "obfs", "obfs-password", "alpn")
                    : Arrays.asList("security", "type", "encryption", "sni", "pbk", "sid", "fp", "flow", "insecure",
                    "allowInsecure", "alpn", "headerType", "serviceName", "mode", "authority", "path", "host", "spx");
            for (String key : query.keySet()) {
                if (!allowed.contains(key)) throw invalid("Ссылка содержит неподдерживаемые параметры подключения.");
            }
            // spx is only Xray's crawler path for REALITY probes; the connection is the same without it.
            if (!query.getOrDefault("spx", "/").equals("/")) throw invalid("Параметр spx от Xray не поддерживается.");
            // ALPN is negotiated by the core itself: h3 for Hysteria2, h2/http/1.1 for TLS.
            for (String key : new String[] {"insecure", "allowInsecure"}) {
                String insecure = query.getOrDefault(key, "0");
                if (!insecure.equals("0") && !insecure.equals("false")) throw invalid("Отключение проверки TLS запрещено.");
            }
            JSONObject flat = new JSONObject().put("type", hy2 ? "hysteria2" : "vless")
                    .put("server", server).put("port", Integer.parseInt(port))
                    .put("serverName", query.getOrDefault("sni", ""));
            if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) flat.put("name", decode(uri.getRawFragment()));
            if (hy2) {
                flat.put("password", credential);
                String obfs = query.getOrDefault("obfs", "");
                String obfsPassword = query.getOrDefault("obfs-password", "");
                if (!obfs.isEmpty() && !obfs.equals("salamander")) throw invalid("Поддерживается только обфускация salamander.");
                if (obfs.isEmpty() != obfsPassword.isEmpty()) throw invalid("Нужно указать salamander и его пароль вместе.");
                flat.put("obfsPassword", obfsPassword);
            } else {
                String security = query.getOrDefault("security", "");
                if (!security.equals("reality") && !security.equals("tls")) throw invalid("Для VLESS поддерживаются REALITY и TLS.");
                String transport = query.getOrDefault("type", "tcp");
                if (transport.equals("raw")) transport = "tcp";
                if (!transport.equals("tcp") && !transport.equals("grpc") && !transport.equals("ws")) {
                    throw invalid("Транспорт VLESS «" + transport + "» не поддерживается.");
                }
                if (!query.getOrDefault("encryption", "none").equals("none")) throw invalid("Поддерживается только encryption=none для VLESS.");
                if (!query.getOrDefault("headerType", "none").equals("none")) throw invalid("Маскировка заголовков TCP не поддерживается.");
                if (!query.getOrDefault("mode", "gun").equals("gun")) throw invalid("Режим gRPC multi не поддерживается.");
                if (!query.getOrDefault("authority", "").isEmpty()) throw invalid("Параметр authority для gRPC не поддерживается.");
                flat.put("uuid", credential).put("security", security).put("transport", transport)
                        .put("realityPublicKey", query.getOrDefault("pbk", ""))
                        .put("shortId", query.getOrDefault("sid", ""))
                        .put("fingerprint", query.getOrDefault("fp", ""))
                        .put("flow", query.getOrDefault("flow", ""));
                if (transport.equals("grpc")) flat.put("path", query.getOrDefault("serviceName", ""));
                if (transport.equals("ws")) flat.put("path", query.getOrDefault("path", "")).put("host", query.getOrDefault("host", ""));
            }
            return ConnectionProfile.fromJson(flat);
        } catch (URISyntaxException | JSONException e) {
            throw invalid("Некорректная ссылка подключения.");
        }
    }

    /** socks5://, http://, https:// as Pocket Windows reads them: optional user:pass, no query, IPv4 or name. */
    private static ConnectionProfile parseProxyUri(String scheme, String credential, String endpoint, URI uri)
            throws JSONException {
        if (uri.getRawQuery() != null) throw invalid("Параметры в ссылке прокси не поддерживаются.");
        String port = scheme.equals("socks5") ? "1080" : scheme.equals("https") ? "443" : "80";
        int colon = endpoint.lastIndexOf(':');
        if (endpoint.startsWith("[") || colon != endpoint.indexOf(':')) throw invalid("IPv6-адрес сервера не поддерживается.");
        String server = endpoint;
        if (colon >= 0) {
            server = endpoint.substring(0, colon);
            port = endpoint.substring(colon + 1);
        }
        if (!port.matches("[0-9]{1,5}")) throw invalid("Некорректный порт подключения.");
        JSONObject flat = new JSONObject().put("type", scheme).put("server", server).put("port", Integer.parseInt(port));
        if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) flat.put("name", decode(uri.getRawFragment()));
        if (!credential.isEmpty()) {
            int split = credential.indexOf(':');
            flat.put("username", decode(split < 0 ? credential : credential.substring(0, split)))
                    .put("password", split < 0 ? "" : decode(credential.substring(split + 1)));
        }
        return ConnectionProfile.fromJson(flat);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) return result;
        String[] fields = raw.split("&", -1);
        if (fields.length > 32) throw invalid("Слишком много параметров ссылки.");
        for (String field : fields) {
            int equal = field.indexOf('=');
            if (equal <= 0) throw invalid("Некорректные параметры ссылки.");
            String key = decode(field.substring(0, equal));
            if (result.containsKey(key)) throw invalid("Повторяющиеся параметры ссылки неоднозначны.");
            result.put(key, decode(field.substring(equal + 1)));
        }
        return result;
    }

    private static String decode(String raw) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < raw.length();) {
                if (raw.charAt(i) == '%') {
                    if (i + 2 >= raw.length()) throw invalid("Некорректная кодировка ссылки.");
                    int high = Character.digit(raw.charAt(i + 1), 16);
                    int low = Character.digit(raw.charAt(i + 2), 16);
                    if (high < 0 || low < 0) throw invalid("Некорректная кодировка ссылки.");
                    bytes.write(high * 16 + low);
                    i += 3;
                } else {
                    int end = raw.indexOf('%', i);
                    if (end < 0) end = raw.length();
                    byte[] literal = raw.substring(i, end).getBytes(StandardCharsets.UTF_8);
                    bytes.write(literal, 0, literal.length);
                    i = end;
                }
            }
            // URI decoding preserves literal '+' and rejects malformed UTF-8 instead of replacing it.
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw invalid("Некорректная кодировка ссылки.");
        }
    }

    private static JSONObject object(JSONObject parent, String key) {
        Object value = parent.opt(key);
        if (!(value instanceof JSONObject)) throw invalid("Отсутствует обязательный объект подключения.");
        return (JSONObject) value;
    }

    private static void requireTrue(JSONObject object, String key) {
        if (!Boolean.TRUE.equals(object.opt(key))) throw invalid("Для подключения требуется включённый TLS/REALITY/uTLS.");
    }

    private static void checkDepth(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                else if (c < 32) throw invalid("Некорректный JSON подключения.");
            } else if (c == '"') quoted = true;
            else if (c == '\'' || c == '/') throw invalid("Ожидается JSON без комментариев и с двойными кавычками.");
            else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) throw invalid("Превышена допустимая вложенность JSON.");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw invalid("Некорректный JSON подключения.");
            }
        }
        if (quoted || depth != 0) throw invalid("Незавершённый JSON подключения.");
    }
}
