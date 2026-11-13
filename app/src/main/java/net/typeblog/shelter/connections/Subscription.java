package net.typeblog.shelter.connections;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.typeblog.shelter.connections.ConnectionProfile.invalid;

/**
 * A provider's subscription: its link and what the provider reports about it, in the headers Happ
 * popularised (profile-title, subscription-userinfo, profile-update-interval, support-url, announce…).
 * The link itself is a secret: it is kept only in the encrypted store and never shown in errors.
 */
public final class Subscription {
    public static final int DEFAULT_INTERVAL_HOURS = 12;
    /** Start of the error a provider gives when it serves only devices that send x-hwid; the interface offers consent. */
    public static final String NEEDS_DEVICE = "Провайдер выдаёт серверы только устройствам, которые сообщают идентификатор.";
    public final String id, url, title, supportUrl, webUrl, announce, error;
    /** Servers the provider listed that Pocket cannot connect, one «name: reason» per line; never secrets. */
    public final String skipped;
    /** The user allowed sending this install's random device id (x-hwid) to this provider. */
    public final boolean hwid;
    /** Bytes; total 0 means unlimited. Expire is Unix seconds, 0 when the provider gives no date. */
    public final long upload, download, total, expire;
    public final int intervalHours;
    /** Wall-clock milliseconds of the last successful update, and of the last attempt. */
    public final long updated, attempted;

    private Subscription(String id, String url, String title, String supportUrl, String webUrl, String announce,
                         String error, long upload, long download, long total, long expire, int intervalHours,
                         long updated, long attempted, String skipped, boolean hwid) {
        this.skipped = skipped; this.hwid = hwid;
        this.id = id; this.url = url; this.title = title; this.supportUrl = supportUrl; this.webUrl = webUrl;
        this.announce = announce; this.error = error; this.upload = upload; this.download = download;
        this.total = total; this.expire = expire; this.intervalHours = intervalHours;
        this.updated = updated; this.attempted = attempted;
    }

    /** Accepts a https:// subscription link, also wrapped as happ://add/… or sub://…; anything else is null. */
    public static String subscriptionUrl(String text) {
        if (text == null) return null;
        String value = text.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("happ://add/")) value = value.substring("happ://add/".length());
        else if (lower.startsWith("sub://")) {
            try { value = new String(Base64.getDecoder().decode(value.substring(6).trim()), StandardCharsets.UTF_8).trim(); }
            catch (IllegalArgumentException e) { return null; }
        }
        if (value.length() > 4096 || value.matches(".*\\s.*")) return null;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) return null;
            // https://host:port alone is an HTTPS proxy; a subscription always has a path or a query.
            boolean path = uri.getRawPath() != null && uri.getRawPath().length() > 1;
            if (!path && uri.getRawQuery() == null) return null;
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static Subscription create(String id, String url) {
        return new Subscription(id, url, "", "", "", "", "", 0, 0, 0, 0, DEFAULT_INTERVAL_HOURS, 0, 0, "", false);
    }

    /** The same subscription after a successful download: provider fields are replaced, the link stays. */
    public Subscription updated(Map<String, String> headers, String body, long now) {
        Map<String, String> values = new HashMap<>();
        // Happ also reads «#key: value» comments at the top of the body; real headers take priority.
        for (String line : body.split("\n", 64)) {
            line = line.trim();
            if (!line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon > 1) values.put(line.substring(1, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) values.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().trim());
        }
        String name = text(decoded(values.get("profile-title")), 128);
        if (name.isEmpty()) name = filename(values.get("content-disposition"));
        if (name.isEmpty()) name = title.isEmpty() ? host(url) : title;
        long up = 0, down = 0, all = 0, until = 0;
        String info = values.get("subscription-userinfo");
        if (info != null) {
            for (String part : info.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length != 2) continue;
                long number = number(pair[1]);
                switch (pair[0].trim().toLowerCase(Locale.ROOT)) {
                    case "upload": up = number; break;
                    case "download": down = number; break;
                    case "total": all = number; break;
                    case "expire": until = number; break;
                }
            }
        }
        int hours = (int) Math.min(24 * 7, number(values.get("profile-update-interval")));
        return new Subscription(id, url, name, link(values.get("support-url")), link(values.get("profile-web-page-url")),
                text(decoded(values.get("announce")), 500), "", up, down, all, until,
                hours > 0 ? hours : DEFAULT_INTERVAL_HOURS, now, now, skipped, hwid);
    }

    /** Same subscription with the list of servers Pocket could not use. */
    public Subscription withSkipped(List<String> warnings) {
        StringBuilder text = new StringBuilder();
        for (String warning : warnings) {
            if (text.length() > 0) text.append('\n');
            text.append(text(warning, 200));
            if (text.length() > 4000) break;
        }
        return new Subscription(id, url, title, supportUrl, webUrl, announce, error, upload, download, total, expire,
                intervalHours, updated, attempted, text.toString(), hwid);
    }

    public Subscription withHwid(boolean allowed) {
        return new Subscription(id, url, title, supportUrl, webUrl, announce, error, upload, download, total, expire,
                intervalHours, updated, attempted, skipped, allowed);
    }

    public Subscription failed(String message, long now) {
        return new Subscription(id, url, title, supportUrl, webUrl, announce, text(message, 200), upload, download,
                total, expire, intervalHours, updated, now, skipped, hwid);
    }

    /** Due when the provider's interval passed since the last success; failures retry after 15 minutes. */
    public boolean due(long now) {
        if (now - attempted < 15 * 60_000L && attempted <= now) return false;
        return updated == 0 || updated > now || now - updated >= intervalHours * 3_600_000L;
    }

    public JSONObject toJson(boolean includeUrl) {
        try {
            JSONObject json = new JSONObject().put("id", id).put("title", title.isEmpty() ? host(url) : title)
                    .put("supportUrl", supportUrl).put("webUrl", webUrl).put("announce", announce).put("error", error)
                    .put("upload", upload).put("download", download).put("total", total).put("expire", expire)
                    .put("intervalHours", intervalHours).put("updated", updated).put("attempted", attempted)
                    .put("skipped", skipped).put("hwid", hwid);
            if (includeUrl) json.put("url", url).put("rawTitle", title);
            return json;
        } catch (JSONException e) {
            throw invalid("Не удалось сохранить подписку.");
        }
    }

    public static Subscription fromJson(JSONObject json) {
        String url = subscriptionUrl(json.optString("url"));
        String id = json.optString("id");
        if (url == null || id.isEmpty() || id.length() > 128) throw invalid("Подписка повреждена.");
        return new Subscription(id, url, json.optString("rawTitle"), json.optString("supportUrl"), json.optString("webUrl"),
                json.optString("announce"), json.optString("error"), json.optLong("upload"), json.optLong("download"),
                json.optLong("total"), json.optLong("expire"), Math.max(1, json.optInt("intervalHours", DEFAULT_INTERVAL_HOURS)),
                json.optLong("updated"), json.optLong("attempted"), json.optString("skipped"), json.optBoolean("hwid"));
    }

    // ---- Download ----

    /** A failure whose message is safe and readable; any other IOException is a plain network error. */
    public static final class Failure extends IOException {
        public Failure(String message) { super(message); }
    }

    public static final class Response {
        public final Map<String, String> headers;
        public final String body;

        Response(Map<String, String> headers, String body) { this.headers = headers; this.body = body; }
    }

    /** HTTPS only, at most three HTTPS redirects and 1 MiB; errors never contain the link. */
    public static Response download(String url, String userAgent, Map<String, String> extraHeaders) throws IOException {
        String current = url;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Accept", "*/*");
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && redirects < 3) {
                    String next = connection.getHeaderField("Location");
                    String resolved = next == null ? null : subscriptionUrl(URI.create(current).resolve(next.trim()).toString());
                    if (resolved == null) throw new Failure("Подписка перенаправляет на небезопасный адрес.");
                    current = resolved;
                    continue;
                }
                if (code == 404 || code == 403 || code == 401) throw new Failure("Провайдер не нашёл подписку (код " + code + "). Проверь ссылку или срок оплаты.");
                if (code != 200) throw new Failure("Сервер подписки ответил кодом " + code + ".");
                Map<String, String> headers = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && !entry.getValue().isEmpty()) headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().get(0));
                }
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (output.size() + count > ProfileImporter.MAX_INPUT_BYTES) throw new Failure("Подписка больше 1 МиБ.");
                        output.write(buffer, 0, count);
                    }
                    String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(output.toByteArray())).toString();
                    return new Response(headers, body);
                } catch (CharacterCodingException e) {
                    throw new Failure("Подписка содержит некорректный текст.");
                }
            } catch (IllegalArgumentException e) {
                throw new Failure("Некорректный адрес подписки.");
            } finally {
                connection.disconnect();
            }
        }
    }

    // ---- Helpers ----

    /** Happ encodes non-ASCII titles and announcements as «base64:…». */
    static String decoded(String value) {
        if (value == null) return "";
        if (!value.regionMatches(true, 0, "base64:", 0, 7)) return value;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getMimeDecoder().decode(value.substring(7).trim()))).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return "";
        }
    }

    /** Printable text only, trimmed to a length the interface can show. */
    static String text(String value, int max) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < max; i++) {
            char c = value.charAt(i);
            if (c == '\n' || !Character.isISOControl(c)) result.append(c);
        }
        if (result.length() > 0 && Character.isHighSurrogate(result.charAt(result.length() - 1))) result.setLength(result.length() - 1);
        return result.toString().trim();
    }

    private static String link(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.length() > 512 || !(value.startsWith("https://") || value.startsWith("tg://"))) return "";
        try { new URI(value); return value; } catch (URISyntaxException e) { return ""; }
    }

    private static String filename(String disposition) {
        if (disposition == null) return "";
        int at = disposition.toLowerCase(Locale.ROOT).indexOf("filename=");
        if (at < 0) return "";
        String name = disposition.substring(at + 9).trim().replace("\"", "");
        int end = name.indexOf(';');
        return text(end < 0 ? name : name.substring(0, end), 128);
    }

    private static long number(String value) {
        if (value == null) return 0;
        try {
            // Panels send «1.07E11» or «123.0» as often as plain integers.
            double number = Double.parseDouble(value.trim());
            return number > 0 && number < 9e18 ? (long) number : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String host(String url) {
        try { String host = new URI(url).getHost(); return host == null ? "Подписка" : host; }
        catch (URISyntaxException e) { return "Подписка"; }
    }
}
