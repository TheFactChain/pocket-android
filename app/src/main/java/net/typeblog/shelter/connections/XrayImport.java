package net.typeblog.shelter.connections;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import static net.typeblog.shelter.connections.ConnectionProfile.invalid;
import static net.typeblog.shelter.connections.ConnectionProfile.string;

/**
 * Xray configs as Happ «JSON» subscriptions serve them: a list of full configs, one proxy server each.
 * Only the server's connection parameters are taken; routing, DNS and every other section are ignored.
 * Xray has many fields the core does not need, so unknown fields are ignored, but anything that would
 * weaken the connection (no TLS check, chained servers, unknown masks) skips the server with a warning.
 */
final class XrayImport {
    private static final int MAX_RECORDS = 256;

    private XrayImport() { }

    /** A config whose outbounds use Xray's «protocol» + «settings» rather than sing-box's «type». */
    static boolean isXrayConfig(JSONObject json) {
        JSONArray outbounds = json.optJSONArray("outbounds");
        if (outbounds == null) return false;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.optJSONObject(i);
            if (outbound != null && outbound.has("protocol") && outbound.has("settings")) return true;
        }
        return false;
    }

    static void parseList(JSONArray configs, List<ConnectionProfile> profiles, List<String> warnings) {
        if (configs.length() > MAX_RECORDS) throw invalid("Слишком много записей: допускается не более 256.");
        for (int i = 0; i < configs.length(); i++) {
            JSONObject config = configs.optJSONObject(i);
            String label = "Запись " + (i + 1);
            try {
                if (config == null || !isXrayConfig(config)) throw invalid("ожидается конфигурация Xray.");
                String remarks = config.optString("remarks", "").trim();
                if (!remarks.isEmpty()) label = "«" + remarks + "»";
                ConnectionProfile profile = parseConfig(config, remarks);
                if (profile != null) profiles.add(profile);
            } catch (IllegalArgumentException e) {
                warnings.add(label + " пропущено: " + e.getMessage());
            }
        }
    }

    private static ConnectionProfile parseConfig(JSONObject config, String remarks) {
        JSONArray outbounds = config.optJSONArray("outbounds");
        JSONObject proxy = null;
        for (int i = 0; outbounds != null && i < outbounds.length(); i++) {
            JSONObject outbound = outbounds.optJSONObject(i);
            if (outbound == null) continue;
            String protocol = outbound.optString("protocol");
            if (protocol.equals("freedom") || protocol.equals("blackhole") || protocol.equals("dns")
                    || protocol.equals("loopback")) continue;
            // The first proxy is the server the config uses by default; «proxy» is the usual tag.
            if (proxy == null || "proxy".equals(outbound.optString("tag"))) proxy = outbound;
        }
        if (proxy == null) throw invalid("в конфигурации нет сервера.");
        JSONObject stream = proxy.optJSONObject("streamSettings");
        if (stream == null) stream = new JSONObject();
        JSONObject sockopt = stream.optJSONObject("sockopt");
        if ((sockopt != null && !sockopt.optString("dialerProxy").isEmpty()) || proxy.has("proxySettings")) {
            throw invalid("цепочки серверов (Double VPN) пока не поддерживаются.");
        }
        // Xray mux is only an optimisation: servers accept the same user without it.
        try {
            String protocol = proxy.optString("protocol");
            JSONObject flat;
            if (protocol.equals("vless")) flat = vless(proxy.getJSONObject("settings"), stream);
            else if (protocol.equals("hysteria") || protocol.equals("hysteria2")) flat = hysteria(proxy.getJSONObject("settings"), stream);
            else throw invalid("протокол " + label(protocol) + " пока не поддерживается.");
            if (!remarks.isEmpty()) flat.put("name", remarks.length() > 256 ? remarks.substring(0, 256) : remarks);
            return ConnectionProfile.fromJson(flat);
        } catch (JSONException e) {
            throw invalid("некорректные поля сервера.");
        }
    }

    private static JSONObject vless(JSONObject settings, JSONObject stream) throws JSONException {
        JSONObject server = settings;
        JSONObject user = settings;
        JSONArray vnext = settings.optJSONArray("vnext");
        if (vnext != null) {
            if (vnext.length() != 1) throw invalid("ожидается один сервер VLESS.");
            server = vnext.getJSONObject(0);
            JSONArray users = server.optJSONArray("users");
            if (users == null || users.length() != 1) throw invalid("ожидается один пользователь VLESS.");
            user = users.getJSONObject(0);
        }
        String encryption = user.optString("encryption", "none");
        if (!encryption.isEmpty() && !encryption.equals("none")) throw invalid("поддерживается только encryption=none.");
        String network = stream.optString("network", "tcp");
        if (network.equals("raw")) network = "tcp";
        JSONObject flat = endpoint(server).put("type", "vless").put("uuid", string(user, "id", ""))
                .put("flow", string(user, "flow", ""));
        if (network.equals("tcp")) {
            JSONObject tcp = first(stream, "tcpSettings", "rawSettings");
            JSONObject header = tcp == null ? null : tcp.optJSONObject("header");
            if (header != null && !header.optString("type", "none").equals("none")) throw invalid("маскировка заголовков TCP не поддерживается.");
        } else if (network.equals("grpc")) {
            JSONObject grpc = stream.optJSONObject("grpcSettings");
            if (grpc != null && grpc.optBoolean("multiMode")) throw invalid("режим gRPC multi не поддерживается.");
            if (grpc != null && !grpc.optString("authority").isEmpty()) throw invalid("параметр authority для gRPC не поддерживается.");
            flat.put("transport", "grpc").put("path", grpc == null ? "" : string(grpc, "serviceName", ""));
        } else if (network.equals("ws")) {
            JSONObject ws = stream.optJSONObject("wsSettings");
            flat.put("transport", "ws");
            if (ws != null) {
                flat.put("path", string(ws, "path", ""));
                JSONObject headers = ws.optJSONObject("headers");
                String host = ws.optString("host", headers == null ? "" : headers.optString("Host", ""));
                if (!host.isEmpty()) flat.put("host", host);
            }
        } else throw invalid("транспорт " + label(network) + " пока не поддерживается.");
        JSONObject mask = stream.optJSONObject("finalmask");
        JSONArray tcpMask = mask == null ? null : mask.optJSONArray("tcp");
        for (int i = 0; tcpMask != null && i < tcpMask.length(); i++) {
            // The core splits the hello by its own sizes; the provider's lengths and intervals only tune it.
            if (tcpMask.getJSONObject(i).optString("type").equals("fragment")) flat.put("fragment", true);
        }
        String security = stream.optString("security", "none");
        if (security.equals("reality")) {
            JSONObject reality = stream.optJSONObject("realitySettings");
            if (reality == null) throw invalid("нет параметров REALITY.");
            String spider = reality.optString("spiderX", "");
            if (!spider.isEmpty() && !spider.equals("/")) throw invalid("параметр spiderX не поддерживается.");
            if (!reality.optString("mldsa65Verify").isEmpty()) throw invalid("постквантовая проверка REALITY не поддерживается.");
            flat.put("security", "reality").put("serverName", string(reality, "serverName", ""))
                    .put("realityPublicKey", string(reality, "publicKey", ""))
                    .put("shortId", string(reality, "shortId", ""))
                    .put("fingerprint", string(reality, "fingerprint", ""));
        } else if (security.equals("tls")) {
            JSONObject tls = stream.optJSONObject("tlsSettings");
            if (tls == null) tls = new JSONObject();
            requireVerified(tls);
            flat.put("security", "tls").put("serverName", string(tls, "serverName", ""))
                    .put("fingerprint", string(tls, "fingerprint", ""));
        } else throw invalid("сервер без шифрования TLS/REALITY не поддерживается.");
        return flat;
    }

    private static JSONObject hysteria(JSONObject settings, JSONObject stream) throws JSONException {
        JSONObject server = settings;
        JSONArray servers = settings.optJSONArray("servers");
        if (servers != null) {
            if (servers.length() != 1) throw invalid("ожидается один сервер Hysteria.");
            server = servers.getJSONObject(0);
        }
        if (settings.has("version") && settings.optInt("version") != 2) throw invalid("поддерживается только Hysteria 2.");
        String network = stream.optString("network", stream.optString("method", "hysteria"));
        if (!network.equals("hysteria")) throw invalid("Hysteria поверх транспорта " + label(network) + " не поддерживается.");
        JSONObject options = stream.optJSONObject("hysteriaSettings");
        String auth = options == null ? "" : string(options, "auth", "");
        if (auth.isEmpty()) auth = string(server, "password", "");
        if (!stream.optString("security", "tls").equals("tls")) throw invalid("Hysteria требует TLS.");
        JSONObject tls = stream.optJSONObject("tlsSettings");
        if (tls == null) tls = new JSONObject();
        requireVerified(tls);
        JSONObject flat = endpoint(server).put("type", "hysteria2").put("password", auth)
                .put("serverName", string(tls, "serverName", ""));
        JSONObject mask = stream.optJSONObject("finalmask");
        JSONArray udp = mask == null ? null : mask.optJSONArray("udp");
        // quicParams only tune the client's QUIC (congestion control); they hide nothing, so the core's defaults do.
        java.util.Iterator<String> keys = mask == null ? java.util.Collections.<String>emptyIterator() : mask.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.equals("udp") && !key.equals("quicParams")) throw invalid("маскировка трафика не поддерживается.");
        }
        for (int i = 0; udp != null && i < udp.length(); i++) {
            JSONObject item = udp.getJSONObject(i);
            JSONObject options2 = item.optJSONObject("settings");
            if (udp.length() != 1 || !item.optString("type").equals("salamander") || options2 == null
                    || !options2.optString("packetSize").isEmpty()) {
                throw invalid("поддерживается только маскировка Salamander.");
            }
            flat.put("obfsPassword", string(options2, "password", ""));
        }
        return flat;
    }

    private static JSONObject endpoint(JSONObject server) throws JSONException {
        Object port = server.opt("port");
        if (port instanceof String && ((String) port).matches("[0-9]{1,5}")) port = Integer.parseInt((String) port);
        if (!(port instanceof Number)) throw invalid("некорректный порт сервера.");
        return new JSONObject().put("server", string(server, "address", "")).put("port", port);
    }

    private static void requireVerified(JSONObject tls) {
        if (tls.optBoolean("allowInsecure") || tls.optBoolean("insecure")) throw invalid("отключение проверки TLS запрещено.");
        if (tls.has("pinnedPeerCertificateChainSha256") || tls.has("pinnedPeerCertSha256")) {
            throw invalid("закрепление сертификата пока не поддерживается.");
        }
    }

    private static JSONObject first(JSONObject parent, String... keys) {
        for (String key : keys) if (parent.optJSONObject(key) != null) return parent.optJSONObject(key);
        return null;
    }

    /** Only short identifier-like names reach a warning; anything else could be secret text. */
    private static String label(String value) {
        return value.matches("[A-Za-z0-9_-]{1,24}") ? value : "этого типа";
    }
}
