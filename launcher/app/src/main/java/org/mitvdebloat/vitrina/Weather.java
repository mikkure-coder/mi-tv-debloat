package org.mitvdebloat.vitrina;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Погода от норвежской метеослужбы (api.met.no): без ключа, но просит
 * представиться в User-Agent. Open-Meteo из домашней сети приставки
 * недоступен (проверено 2026-10-25). Кэш 30 минут.
 */
final class Weather {
    private Weather() {}

    static final class Hour { String time; int temp, code; boolean day; }

    static final class Data {
        int temp, code, max, min;
        boolean day;
        String sunrise = "", sunset = "";
        final List<Hour> hours = new ArrayList<>();
    }

    interface Callback { void done(Data d); }

    static void load(Context c, Callback cb) {
        final double lat = Config.lat(c), lon = Config.lon(c);
        String url = String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", lat, lon);
        java.util.Map<String, String> h = java.util.Collections.singletonMap("User-Agent",
                "Vitrina/0.1 github.com/mikkure-coder/mi-tv-debloat");
        Net.text(c, url, h, 30 * 60_000L, body -> {
            if (body == null) { cb.done(null); return; }
            try {
                JSONArray ts = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
                java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.text.SimpleDateFormat hhmm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
                java.text.SimpleDateFormat dayKey = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
                long now = System.currentTimeMillis();
                String today = dayKey.format(new java.util.Date(now));
                long[] sun = sunTimes(lat, lon, now);
                Data d = new Data();
                d.sunrise = sun == null ? "" : hhmm.format(new java.util.Date(sun[0]));
                d.sunset = sun == null ? "" : hhmm.format(new java.util.Date(sun[1]));
                d.max = Integer.MIN_VALUE; d.min = Integer.MAX_VALUE;
                boolean haveNow = false;
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject e = ts.getJSONObject(i);
                    long t = iso.parse(e.getString("time")).getTime();
                    JSONObject data = e.getJSONObject("data");
                    int temp = (int) Math.round(data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature"));
                    JSONObject n1 = data.optJSONObject("next_1_hours");
                    if (n1 == null) n1 = data.optJSONObject("next_6_hours");
                    String sym = n1 == null ? "cloudy" : n1.getJSONObject("summary").getString("symbol_code");
                    if (dayKey.format(new java.util.Date(t)).equals(today)) { d.max = Math.max(d.max, temp); d.min = Math.min(d.min, temp); }
                    if (t + 3600_000L <= now) continue;            // этот час уже прошёл
                    boolean day = isDay(sym, t, lat, lon);
                    if (!haveNow) { d.temp = temp; d.code = code(sym); d.day = day; haveNow = true; continue; }
                    if (d.hours.size() < 6) {
                        Hour x = new Hour();
                        x.time = hhmm.format(new java.util.Date(t));
                        x.temp = temp; x.code = code(sym); x.day = day;
                        d.hours.add(x);
                    }
                }
                if (!haveNow) { cb.done(null); return; }
                if (d.max == Integer.MIN_VALUE) { d.max = d.temp; d.min = d.temp; }
                cb.done(d);
            } catch (Exception e) {
                cb.done(null);
            }
        });
    }

    /** Значок met.no («partlycloudy_day», «lightrainshowers_night»…) → код погоды ВМО. */
    static int code(String sym) {
        String s = sym.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
        if (s.contains("thunder")) return 95;
        if (s.contains("sleet")) return 66;
        if (s.contains("snow")) return s.startsWith("heavy") ? 75 : s.startsWith("light") ? 71 : 73;
        if (s.contains("rain")) {
            if (s.contains("showers")) return 80;
            return s.startsWith("heavy") ? 65 : s.startsWith("light") ? 61 : 63;
        }
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "fog": return 45;
            default: return 3;
        }
    }

    private static boolean isDay(String sym, long t, double lat, double lon) {
        if (sym.endsWith("_day")) return true;
        if (sym.endsWith("_night") || sym.endsWith("_polartwilight")) return false;
        long[] s = sunTimes(lat, lon, t);
        return s == null || (t >= s[0] && t < s[1]);
    }

    /** Восход и закат (мс UTC) для суток, в которые попадает t. Формула «уравнения восхода». */
    static long[] sunTimes(double lat, double lon, long t) {
        double jd = t / 86400000.0 + 2440587.5;
        double n = Math.ceil(jd - 2451545.0 + 0.0008 - 0.5);
        double js = n - lon / 360.0;
        double m = Math.toRadians((357.5291 + 0.98560028 * js) % 360);
        double cc = 1.9148 * Math.sin(m) + 0.02 * Math.sin(2 * m) + 0.0003 * Math.sin(3 * m);
        double lam = Math.toRadians((Math.toDegrees(m) + cc + 180 + 102.9372) % 360);
        double jt = 2451545.0 + js + 0.0053 * Math.sin(m) - 0.0069 * Math.sin(2 * lam);
        double dec = Math.asin(Math.sin(lam) * Math.sin(Math.toRadians(23.4397)));
        double phi = Math.toRadians(lat);
        double cw = (Math.sin(Math.toRadians(-0.833)) - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
        if (cw < -1 || cw > 1) return null;  // полярный день или ночь
        double w = Math.toDegrees(Math.acos(cw)) / 360.0;
        return new long[]{(long) ((jt - w - 2440587.5) * 86400000.0), (long) ((jt + w - 2440587.5) * 86400000.0)};
    }

    private static String hm(String iso) { return iso.length() >= 16 ? iso.substring(11, 16) : iso; }

    /** Код погоды ВМО → слова. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Ясно";
            case 1: return "Малооблачно";
            case 2: return "Переменная облачность";
            case 3: return "Пасмурно";
            case 45: case 48: return "Туман";
            case 51: case 53: case 55: return "Морось";
            case 56: case 57: return "Ледяная морось";
            case 61: return "Небольшой дождь";
            case 63: return "Дождь";
            case 65: return "Сильный дождь";
            case 66: case 67: return "Ледяной дождь";
            case 71: return "Небольшой снег";
            case 73: return "Снег";
            case 75: return "Сильный снег";
            case 77: return "Снежная крупа";
            case 80: case 81: case 82: return "Ливень";
            case 85: case 86: return "Снегопад";
            case 95: return "Гроза";
            case 96: case 99: return "Гроза с градом";
            default: return "";
        }
    }

    static WeatherIcon.Kind kind(int code, boolean day) {
        if (code == 0) return day ? WeatherIcon.Kind.SUN : WeatherIcon.Kind.MOON;
        if (code == 1 || code == 2) return day ? WeatherIcon.Kind.PARTLY_DAY : WeatherIcon.Kind.PARTLY_NIGHT;
        if (code == 3) return WeatherIcon.Kind.CLOUD;
        if (code == 45 || code == 48) return WeatherIcon.Kind.FOG;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return WeatherIcon.Kind.SNOW;
        if (code >= 95) return WeatherIcon.Kind.STORM;
        return WeatherIcon.Kind.RAIN;
    }
}
