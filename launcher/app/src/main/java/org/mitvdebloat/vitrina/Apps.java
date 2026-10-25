package org.mitvdebloat.vitrina;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Установленные приложения: те, что можно запустить с ТВ-пульта. */
final class Apps {
    private Apps() {}

    static final class App {
        String pkg;
        CharSequence label;
        Drawable banner, icon;
    }

    static List<App> all(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> map = new LinkedHashMap<>();
        collect(c, pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(c, pm, map, Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>(map.values());
        Collator col = Collator.getInstance(new Locale("ru", "RU"));
        out.sort((a, b) -> col.compare(a.label.toString(), b.label.toString()));
        return out;
    }

    private static void collect(Context c, PackageManager pm, Map<String, App> map, String category) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || map.containsKey(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = ri.loadLabel(pm);
            if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) {
                a.banner = ri.activityInfo.loadBanner(pm);
                if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            }
            a.icon = ri.loadIcon(pm);
            map.put(pkg, a);
        }
    }

    static App find(List<App> all, String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    static boolean launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }
}
