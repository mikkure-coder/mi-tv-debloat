package org.mitvdebloat.vitrina;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Полка постеров: подборки каталога Кинопоиска (kinopoiskapiunofficial.tech,
 * нужен бесплатный ключ). Три вкладки — три подборки, кэш на 12 часов:
 * это 3 запроса из 500 в сутки, которые даёт бесплатный ключ.
 */
final class Kino {
    private Kino() {}

    static final String[] TABS = {"Обзор", "Фильмы", "Сериалы"};
    private static final String[] TYPES = {"TOP_POPULAR_ALL", "TOP_POPULAR_MOVIES", "POPULAR_SERIES"};

    static final class Item {
        int id;
        String title, year, genre, country, poster, cover, type;
        double rating;

        /** «Сериал · Драма · 2024 · КП 7.8» */
        String meta() {
            StringBuilder sb = new StringBuilder("TV_SERIES".equals(type) || "MINI_SERIES".equals(type) ? "Сериал" : "Фильм");
            if (genre != null && !genre.isEmpty()) sb.append(" · ").append(Character.toUpperCase(genre.charAt(0))).append(genre.substring(1));
            if (year != null && !year.isEmpty() && !"null".equals(year)) sb.append(" · ").append(year);
            if (rating > 0) sb.append(" · Кинопоиск ").append(String.format(java.util.Locale.US, "%.1f", rating));
            return sb.toString();
        }
    }

    interface Callback { void done(List<Item> items, String error); }

    /**
     * Демо без ключа (kinopoisk_key = "demo"): восемь известных фильмов, постеры
     * с открытого сервера картинок Кинопоиска. Нужно, чтобы проверить полку
     * до получения ключа. Рейтинги приблизительные.
     */
    private static List<Item> demo() {
        Object[][] d = {
                {258687, "Интерстеллар", "2014", "фантастика", 8.7},
                {447301, "Начало", "2010", "фантастика", 8.7},
                {535341, "1+1", "2011", "драма", 8.8},
                {301, "Матрица", "1999", "фантастика", 8.5},
                {326, "Побег из Шоушенка", "1994", "драма", 9.1},
                {435, "Зелёная миля", "1999", "драма", 9.1},
                {448, "Форрест Гамп", "1994", "драма", 8.9},
                {329, "Список Шиндлера", "1993", "драма", 8.8}};
        List<Item> out = new ArrayList<>();
        for (Object[] r : d) {
            Item it = new Item();
            it.id = (Integer) r[0];
            it.title = (String) r[1];
            it.year = (String) r[2];
            it.genre = (String) r[3];
            it.rating = (Double) r[4];
            it.type = "FILM";
            it.poster = "https://kinopoiskapiunofficial.tech/images/posters/kp_small/" + it.id + ".jpg";
            it.cover = "https://kinopoiskapiunofficial.tech/images/posters/kp/" + it.id + ".jpg";
            out.add(it);
        }
        return out;
    }

    static void load(Context c, int tab, Callback cb) {
        String key = Config.kpKey(c);
        if (key.isEmpty()) { cb.done(Collections.emptyList(), "nokey"); return; }
        if ("demo".equals(key)) { cb.done(demo(), null); return; }
        String url = "https://kinopoiskapiunofficial.tech/api/v2.2/films/collections?type=" + TYPES[tab] + "&page=1";
        Net.text(c, url, Collections.singletonMap("X-API-KEY", key), 12 * 3600_000L, body -> {
            if (body == null) { cb.done(Collections.emptyList(), "net"); return; }
            try {
                JSONArray a = new JSONObject(body).getJSONArray("items");
                List<Item> out = new ArrayList<>();
                for (int i = 0; i < a.length() && out.size() < 14; i++) {
                    JSONObject o = a.getJSONObject(i);
                    Item it = new Item();
                    it.id = o.optInt("kinopoiskId");
                    it.title = o.optString("nameRu", "");
                    if (it.title.isEmpty() || "null".equals(it.title)) it.title = o.optString("nameOriginal", "");
                    if (it.title.isEmpty() || "null".equals(it.title)) continue;
                    it.year = o.optString("year", "");
                    it.type = o.optString("type", "FILM");
                    it.rating = o.optDouble("ratingKinopoisk", 0);
                    if (Double.isNaN(it.rating)) it.rating = 0;
                    it.poster = o.optString("posterUrlPreview", o.optString("posterUrl", ""));
                    it.cover = o.isNull("coverUrl") ? "" : o.optString("coverUrl", "");
                    JSONArray g = o.optJSONArray("genres");
                    if (g != null && g.length() > 0) it.genre = g.getJSONObject(0).optString("genre");
                    out.add(it);
                }
                cb.done(out, null);
            } catch (Exception e) {
                cb.done(Collections.emptyList(), "parse");
            }
        });
    }
}
