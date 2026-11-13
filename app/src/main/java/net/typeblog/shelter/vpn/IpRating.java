package net.typeblog.shelter.vpn;

import org.json.JSONException;
import org.json.JSONObject;

/** A reference rating of an exit address, as the home screen shows it. */
public final class IpRating {
    private IpRating() { }

    /** IPLogs /v1/check: verdict clean, suspicious, vpn_likely or vpn_detected, flags is_*, score 0–1. */
    public static JSONObject fromIpLogs(JSONObject value, String ip) throws JSONException {
        String verdict = value.optString("verdict");
        String risk, text;
        switch (verdict) {
            case "clean": risk = "low"; text = "Чистый"; break;
            case "suspicious": risk = "medium"; text = "Подозрительный"; break;
            case "vpn_likely": risk = "high"; text = "Похоже на VPN или прокси"; break;
            case "vpn_detected": risk = "high"; text = "Определён как VPN или прокси"; break;
            default: risk = "unknown"; text = "Нет оценки";
        }
        StringBuilder flags = new StringBuilder();
        if (value.optBoolean("is_hosting")) flags.append(", хостинг");
        if (value.optBoolean("is_proxy")) flags.append(", прокси");
        if (value.optBoolean("is_tor")) flags.append(", Tor");
        if (value.optBoolean("is_relay")) flags.append(", релей");
        if (value.has("score")) flags.append(String.format(java.util.Locale.ROOT, ", риск %.0f%%", value.optDouble("score") * 100));
        return new JSONObject().put("source", "IPLogs").put("risk", risk).put("summary", text + flags)
                .put("url", "https://iplogs.com/ip/" + ip).put("time", System.currentTimeMillis());
    }
}
