package org.mitvdebloat.vitrina;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Курсы: доллар, евро, юань — по ЦБ РФ; биткоин и эфир — CoinGecko. Кэш 30 минут. */
final class Rates {
    private Rates() {}

    static final class Rate {
        String sign, name;
        double value, change; // change — проценты
    }

    interface Callback { void done(List<Rate> rates); }

    static void load(Context c, Callback cb) {
        final List<Rate> out = new ArrayList<>();
        Net.text(c, "https://www.cbr-xml-daily.ru/daily_json.js", null, 30 * 60_000L, cbr -> {
            try {
                if (cbr != null) {
                    JSONObject v = new JSONObject(cbr).getJSONObject("Valute");
                    add(out, v, "USD", "$", "Доллар");
                    add(out, v, "EUR", "€", "Евро");
                    add(out, v, "CNY", "¥", "Юань");
                }
            } catch (Exception ignored) { }
            Net.text(c, "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum"
                    + "&vs_currencies=rub&include_24hr_change=true", null, 30 * 60_000L, cg -> {
                try {
                    if (cg != null) {
                        JSONObject o = new JSONObject(cg);
                        coin(out, o, "bitcoin", "₿", "Биткоин");
                        coin(out, o, "ethereum", "Ξ", "Эфир");
                    }
                } catch (Exception ignored) { }
                cb.done(out);
            });
        });
    }

    private static void add(List<Rate> out, JSONObject v, String code, String sign, String name) throws Exception {
        JSONObject x = v.getJSONObject(code);
        double nominal = x.optDouble("Nominal", 1);
        Rate r = new Rate();
        r.sign = sign; r.name = name;
        r.value = x.getDouble("Value") / nominal;
        double prev = x.getDouble("Previous") / nominal;
        r.change = prev > 0 ? (r.value - prev) / prev * 100 : 0;
        out.add(r);
    }

    private static void coin(List<Rate> out, JSONObject o, String id, String sign, String name) throws Exception {
        JSONObject x = o.getJSONObject(id);
        Rate r = new Rate();
        r.sign = sign; r.name = name;
        r.value = x.getDouble("rub");
        r.change = x.optDouble("rub_24h_change", 0);
        out.add(r);
    }

    /** 81,23 ₽ · 6,46 млн ₽ · 188 тыс ₽ */
    static String money(double v) {
        Locale ru = new Locale("ru", "RU");
        if (v >= 1_000_000) return String.format(ru, "%.2f млн ₽", v / 1_000_000);
        if (v >= 10_000) return String.format(ru, "%.0f тыс ₽", v / 1000);
        return String.format(ru, "%.2f ₽", v);
    }

    /** Коротко для строки под кнопками: «$ 81,2». */
    static String brief(Rate r) {
        Locale ru = new Locale("ru", "RU");
        if (r.value >= 1_000_000) return String.format(ru, "%s %.2f млн", r.sign, r.value / 1_000_000);
        if (r.value >= 10_000) return String.format(ru, "%s %.0f тыс", r.sign, r.value / 1000);
        return String.format(ru, "%s %.1f", r.sign, r.value);
    }
}
