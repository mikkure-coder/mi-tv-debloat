package org.mitvdebloat.vitrina;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Картинки по адресу: память (LruCache) → диск → сеть. Декодируем сразу
 * в нужный размер, чтобы постеры не съедали память приставки (2 ГБ).
 */
final class Images {
    private Images() {}

    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 6)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
            };

    interface After { void loaded(Bitmap b); }

    static void load(Context c, String url, int w, int h, ImageView into) { load(c, url, w, h, into, null); }

    /** after — вызывается с картинкой, когда она встала в into (нужно знать её пропорции). */
    static void load(Context c, String url, int w, int h, ImageView into, After after) {
        if (url == null || url.isEmpty()) { into.setImageDrawable(null); return; }
        final String key = url + "@" + w + "x" + h;
        into.setTag(R.id.tag_url, key);
        Bitmap m = MEM.get(key);
        if (m != null) { into.setImageBitmap(m); into.setAlpha(1f); if (after != null) after.loaded(m); return; }
        into.setImageDrawable(null);
        final Context app = c.getApplicationContext();
        Net.POOL.execute(() -> {
            Bitmap b = null;
            try {
                File dir = new File(app.getCacheDir(), "img");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, Net.sha1(url));
                byte[] data = f.exists() ? Files.readAllBytes(f.toPath()) : Net.fetch(url, null);
                if (data != null) {
                    if (!f.exists()) try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                    b = decode(data, w, h);
                }
            } catch (Exception ignored) { }
            final Bitmap r = b;
            Net.MAIN.post(() -> {
                if (r == null) return;
                MEM.put(key, r);
                if (key.equals(into.getTag(R.id.tag_url))) {
                    into.setImageBitmap(r);
                    into.setAlpha(0f);
                    into.animate().alpha(1f).setDuration(220).start();
                    if (after != null) after.loaded(r);
                }
            });
        });
    }

    static Bitmap decode(byte[] data, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }
}
