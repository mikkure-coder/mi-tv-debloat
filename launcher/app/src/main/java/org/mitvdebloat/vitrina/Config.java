package org.mitvdebloat.vitrina;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Настройки лаунчера. Хранятся в SharedPreferences, меняются с ноутбука
 * через ConfigReceiver (скрипт 08) или прямо на экране (ряд приложений).
 */
final class Config {
    private Config() {}

    static final String PREFS = "vitrina";
    static final String DOCK = "dock";
    static final String CITY = "city";
    static final String LAT = "lat";
    static final String LON = "lon";

    /** Ряд по умолчанию: приложения, которые есть и на ТВ, и на приставке. */
    static final List<String> DEFAULT_DOCK = Arrays.asList(
            "ru.more.play", "com.vk.tv", "ru.kinopoisk.tv", "ru.rutube.app",
            "ru.mts.mtstv", "com.google.android.youtube.tv", "ru.ivi.client");

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<String> dock(Context c) {
        String s = prefs(c).getString(DOCK, null);
        if (s == null) return new ArrayList<>(DEFAULT_DOCK);
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    static void setDock(Context c, List<String> pkgs) {
        prefs(c).edit().putString(DOCK, String.join(",", pkgs)).apply();
    }

    static String city(Context c) { return prefs(c).getString(CITY, "Москва"); }
    static double lat(Context c) { return Double.longBitsToDouble(prefs(c).getLong(LAT, Double.doubleToLongBits(55.7558))); }
    static double lon(Context c) { return Double.longBitsToDouble(prefs(c).getLong(LON, Double.doubleToLongBits(37.6173))); }
}
