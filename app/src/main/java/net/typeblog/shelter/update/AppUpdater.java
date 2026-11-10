package net.typeblog.shelter.update;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Проверка и установка обновления APK по манифесту. Сеть и диск: только вне главного потока. */
public final class AppUpdater {
    public static final String FEED = "https://github.com/TheFactChain/pocket-android/releases/latest/download/update-manifest.json";
    /** Открытая страница выпусков: там видно каждую версию, её APK и манифест. */
    public static final String RELEASES = "https://github.com/TheFactChain/pocket-android/releases";
    /** Тестовая лента; принимается только при debug = true. */
    public static final String DEBUG_FEED = "http://127.0.0.1:8765/update-manifest.json";

    private static final long MAX_APK = 200L * 1024 * 1024;
    private static final int MAX_MANIFEST = 64 * 1024;
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "schema", "versionCode", "versionName", "url", "sha256", "size", "notes"));

    private AppUpdater() {}

    public static final class Manifest {
        public int versionCode;
        public String versionName, url, sha256, notes = "";
        public long size;
    }

    public interface ProgressListener { void onProgress(long done, long total); }

    public static Manifest parse(String json) { return parse(json, false); }

    /** Строгий разбор: неизвестные ключи и неверные типы отвергаются. debug разрешает http://127.0.0.1. */
    public static Manifest parse(String json, boolean debug) {
        try {
            if (json == null || json.length() > MAX_MANIFEST) throw new IllegalArgumentException("Манифест обновления слишком большой.");
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!KEYS.contains(k)) throw new IllegalArgumentException("Неизвестное поле в манифесте: " + k + ".");
            }
            Object schema = o.opt("schema");
            if (!isInt(schema) || ((Number) schema).longValue() != 1) throw new IllegalArgumentException("Неподдерживаемая версия манифеста.");
            Manifest m = new Manifest();
            Object vc = o.opt("versionCode");
            if (!isInt(vc) || ((Number) vc).longValue() <= 0 || ((Number) vc).longValue() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Неверный versionCode.");
            m.versionCode = ((Number) vc).intValue();
            m.versionName = str(o, "versionName", 32, true);
            m.url = str(o, "url", 2048, true);
            checkUrl(m.url, debug);
            m.sha256 = str(o, "sha256", 64, true);
            if (!SHA.matcher(m.sha256).matches()) throw new IllegalArgumentException("Неверная контрольная сумма SHA-256.");
            Object sz = o.opt("size");
            if (!isInt(sz) || ((Number) sz).longValue() < 1 || ((Number) sz).longValue() > MAX_APK)
                throw new IllegalArgumentException("Неверный размер файла обновления.");
            m.size = ((Number) sz).longValue();
            if (o.has("notes")) m.notes = str(o, "notes", 2000, false);
            return m;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Манифест обновления не распознан.");
        }
    }

    private static boolean isInt(Object v) { return v instanceof Integer || v instanceof Long; }

    private static String str(JSONObject o, String key, int max, boolean required) {
        Object v = o.opt(key);
        if (!(v instanceof String) || (required && ((String) v).isEmpty()) || ((String) v).length() > max)
            throw new IllegalArgumentException("Неверное поле манифеста: " + key + ".");
        return (String) v;
    }

    private static void checkUrl(String url, boolean debug) {
        try {
            URI u = new URI(url);
            boolean https = "https".equals(u.getScheme());
            boolean loopback = debug && "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost());
            if (!(https || loopback) || u.getHost() == null || u.getUserInfo() != null || u.getRawFragment() != null)
                throw new IllegalArgumentException("Недопустимый адрес файла обновления.");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Недопустимый адрес файла обновления.");
        }
    }

    public static boolean isNewer(Manifest m, long installedVersionCode) { return m.versionCode > installedVersionCode; }

    /** Следует редиректам вручную и только на допустимые адреса (GitHub уходит на другой хост). */
    private static HttpURLConnection open(String url, boolean debug) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            try { checkUrl(url, debug); } catch (IllegalArgumentException e) { throw new IOException(e.getMessage()); }
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "PocketAndroid");
            int code = c.getResponseCode();
            if (code == 200) return c;
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (code >= 300 && code < 400 && loc != null) {
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            throw new IOException("Сервер обновлений ответил кодом " + code + ".");
        }
        throw new IOException("Слишком много перенаправлений.");
    }

    public static Manifest fetchManifest(String feedUrl, boolean debug) throws IOException {
        HttpURLConnection c = open(feedUrl, debug);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_MANIFEST) throw new IOException("Манифест обновления слишком большой.");
            }
            return parse(out.toString("UTF-8"), debug);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** Скачивает в update.apk.partial, проверяет размер и SHA-256, переименовывает в update.apk. */
    public static File download(Manifest m, File dir, ProgressListener l, AtomicBoolean cancelled, boolean debug) throws IOException {
        File partial = new File(dir, "update.apk.partial"), apk = new File(dir, "update.apk");
        boolean ok = false;
        HttpURLConnection c = null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать папку обновления.");
            apk.delete();
            c = open(m.url, debug);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled != null && cancelled.get()) throw new IOException("Загрузка отменена.");
                    done += n;
                    if (done > m.size) throw new IOException("Файл обновления больше заявленного размера.");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (l != null) l.onProgress(done, m.size);
                }
            }
            if (done != m.size) throw new IOException("Размер файла обновления не совпал с манифестом.");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            if (!hex.toString().equals(m.sha256)) throw new IOException("Контрольная сумма файла обновления не совпала.");
            if (!partial.renameTo(apk)) throw new IOException("Не удалось сохранить файл обновления.");
            ok = true;
            return apk;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 недоступен.");
        } finally {
            if (c != null) c.disconnect();
            if (!ok) partial.delete();
        }
    }

    /** Ставит APK через PackageInstaller; результат придёт на statusReceiver. */
    public static void install(Context ctx, File apk, IntentSender statusReceiver) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("update.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            s.commit(statusReceiver);
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }
}
