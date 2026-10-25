package org.mitvdebloat.vitrina;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сеть с кэшем на диске. Свежий кэш — без запроса; нет сети — отдаём старый
 * кэш, чтобы главный экран не пустел.
 */
final class Net {
    private Net() {}

    static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback { void done(String body); }

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "net");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static void dropCache(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    static String sha1(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    /** Текст по адресу; maxAgeMs — сколько кэш считается свежим. Ответ — в главном потоке, null при неудаче. */
    static void text(Context c, String url, Map<String, String> headers, long maxAgeMs, Callback cb) {
        final File f = new File(dir(c), sha1(url) + ".txt");
        POOL.execute(() -> {
            String body = null;
            try {
                if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) {
                    body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } else {
                    byte[] b = fetch(url, headers);
                    if (b != null) {
                        body = new String(b, StandardCharsets.UTF_8);
                        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
                    } else if (f.exists()) {
                        body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                Log.w("Vitrina", "net " + url, e);
            }
            final String r = body;
            MAIN.post(() -> cb.done(r));
        });
    }

    /** Сырые байты; null, если ответ не 200. Вызывать не из главного потока. */
    static byte[] fetch(String url, Map<String, String> headers) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(30000);
            h.setReadTimeout(30000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "Vitrina/0.1 (Android TV launcher)");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
                h.setRequestProperty(e.getKey(), e.getValue());
            int code = h.getResponseCode();
            if (code != 200) { Log.w("Vitrina", "HTTP " + code + " " + url); return null; }
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                return o.toByteArray();
            }
        } catch (Exception e) {
            Log.w("Vitrina", "fetch " + url + ": " + e);
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
