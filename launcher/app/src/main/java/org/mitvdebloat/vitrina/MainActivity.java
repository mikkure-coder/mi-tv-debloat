package org.mitvdebloat.vitrina;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Главный экран «Витрины». Две «страницы» на одном полотне:
 *   1) часы и дата, большая карточка погоды, ряд приложений;
 *   2) ниже ряда: входы (на ТВ), ярлыки и курсы.
 * Когда фокус уходит ниже ряда приложений, полотно уезжает вверх, и ряд
 * приложений становится шапкой второй страницы — как в видео.
 */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    // Сетка экрана 960×540 dp (приставка и ТВ: 1920×1080 при плотности 320).
    private static final int SIDE = 48, WEATHER_H = 292;
    private static final int TILE_W = 104, TILE_H = 58, TILE_GAP = 14;

    private LinearLayout canvas, dockRow, page1;
    private View dockPanel, page2;
    private TextView clockText, dateText, ticker;
    private ImageView wifiBtn;
    private LinearLayout weatherBox, ratesBox;
    private boolean shifted;
    private long loadedAt;
    private List<Apps.App> apps = new ArrayList<>();
    private List<Rates.Rate> lastRates = new ArrayList<>();
    private final Runnable clock = new Runnable() {
        @Override public void run() { updateTicker(); Net.MAIN.postDelayed(this, 20_000); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
        Config.prefs(this).registerOnSharedPreferenceChangeListener(this);
        watchNetwork();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDock();
        if (System.currentTimeMillis() - loadedAt > 25 * 60_000L) loadData();
        Net.MAIN.post(clock);
    }

    @Override protected void onPause() {
        super.onPause();
        Net.MAIN.removeCallbacks(clock);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (Config.DOCK.equals(key)) refreshDock();
        else { loadedAt = 0; loadData(); }
    }

    // ---- Вёрстка -----------------------------------------------------------

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        canvas = new LinearLayout(this);
        canvas.setOrientation(LinearLayout.VERTICAL);
        canvas.setClipChildren(false);
        canvas.setClipToPadding(false);
        canvas.setPadding(dp(SIDE), dp(20), dp(SIDE), 0);
        root.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1100)));

        page1 = new LinearLayout(this);
        page1.setOrientation(LinearLayout.VERTICAL);
        page1.setClipChildren(false);
        page1.setClipToPadding(false);
        page1.addView(header(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
        // Погода на первой странице только для глаз: фокус с ряда приложений
        // вверх идёт сразу на кнопки шапки.
        weatherBox = card(false);
        TextView wait = Ui.text(this, 10, R.color.text3, Ui.REGULAR);
        wait.setText("Загружаю погоду…");
        weatherBox.addView(wait);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(WEATHER_H));
        wl.topMargin = dp(8);
        page1.addView(weatherBox, wl);
        canvas.addView(page1);

        dockPanel = dock();
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H + 30));
        dl.topMargin = dp(16);
        canvas.addView(dockPanel, dl);

        page2 = page2();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(20);
        canvas.addView(page2, pl);

        setContentView(root);
        // Фокус переходит между страницами — сдвигаем полотно.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            if (newF == null) return;
            shift(isInside(newF, page2));
        });
    }

    private View header() {
        FrameLayout h = new FrameLayout(this);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clockText = Ui.text(this, 27, R.color.text, Ui.MEDIUM);
        left.addView(clockText);
        dateText = Ui.text(this, 11, R.color.text2, Ui.MEDIUM);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(6);
        left.addView(dateText, dl);
        h.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setClipChildren(false);
        right.setClipToPadding(false);
        LinearLayout btns = new LinearLayout(this);
        btns.setClipChildren(false);
        btns.setClipToPadding(false);
        btns.setGravity(Gravity.END);
        int s = dp(34);
        wifiBtn = Bits.roundButton(this, R.drawable.ic_wifi, s);
        wifiBtn.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        ImageView gear = Bits.roundButton(this, R.drawable.ic_settings, s);
        gear.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(s, s);
        bl.leftMargin = dp(10);
        btns.addView(wifiBtn, bl);
        if (!inputs().isEmpty()) {
            ImageView inp = Bits.roundButton(this, R.drawable.ic_input, s);
            inp.setOnClickListener(v -> { shift(true); page2.post(() -> page2.findFocus()); focusFirst(page2); });
            btns.addView(inp, new LinearLayout.LayoutParams(bl));
        }
        btns.addView(gear, new LinearLayout.LayoutParams(bl));
        right.addView(btns);
        ticker = Ui.text(this, 9.5f, R.color.text3, Ui.REGULAR);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.topMargin = dp(12);
        right.addView(ticker, kl);
        h.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP));
        return h;
    }

    private View dock() {
        FrameLayout p = new FrameLayout(this);
        p.setClipChildren(false);
        p.setClipToPadding(false);
        p.setBackground(Ui.round(getColor(R.color.glass), dp(26), dp(1), getColor(R.color.glass_edge)));
        dockRow = new LinearLayout(this);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(14), 0, dp(14), 0);
        p.addView(dockRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return p;
    }

    private void refreshDock() {
        apps = Apps.all(this);
        View had = getCurrentFocus();
        int focusedIdx = had != null && had.getParent() == dockRow ? dockRow.indexOfChild(had) : -1;
        dockRow.removeAllViews();
        List<String> pkgs = Config.dock(this);
        List<Apps.App> shown = new ArrayList<>();
        for (String pkg : pkgs) { Apps.App a = Apps.find(apps, pkg); if (a != null) shown.add(a); }
        int avail = getResources().getDisplayMetrics().widthPixels - dp(2 * SIDE + 28);
        int max = Math.max(1, (avail + dp(TILE_GAP)) / (dp(TILE_W) + dp(TILE_GAP)));
        if (shown.size() > max) shown = shown.subList(0, max);
        // Ширина плитки — чтобы ряд заполнил панель, но не шире 132 dp при малом числе приложений.
        int k = Math.max(1, shown.size());
        int tw = Math.min(dp(132), (avail - (k - 1) * dp(TILE_GAP)) / k);
        int th = Math.round(tw * 9f / 16f);
        for (Apps.App a : shown) {
            TileView t = new TileView(this, a, tw, th);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tw, th);
            if (dockRow.getChildCount() > 0) lp.leftMargin = dp(TILE_GAP);
            t.setOnClickListener(v -> { if (!Apps.launch(this, a.pkg)) toast("Не запускается: " + a.label); });
            t.setOnLongClickListener(v -> { dockMenu(a); return true; });
            t.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { dockMenu(a); return true; }
                return false;
            });
            dockRow.addView(t, lp);
        }
        if (dockRow.getChildCount() == 0) {
            TextView hint = Ui.text(this, 11, R.color.text2, Ui.REGULAR);
            hint.setText("Ряд пуст. Ниже: «Все приложения» → удерживайте OK на плитке.");
            hint.setFocusable(true);
            dockRow.addView(hint);
        }
        if (focusedIdx >= 0) dockRow.getChildAt(Math.min(focusedIdx, dockRow.getChildCount() - 1)).requestFocus();
        else if (had == null) dockRow.getChildAt(0).requestFocus();
    }

    private void dockMenu(Apps.App a) {
        Bits.menu(this, a.label, new String[]{"Сдвинуть влево", "Сдвинуть вправо", "Убрать с главного экрана", "Все приложения"}, i -> {
            List<String> d = Config.dock(this);
            int k = d.indexOf(a.pkg);
            if (k < 0) return;
            if (i == 0 && k > 0) { d.remove(k); d.add(k - 1, a.pkg); }
            else if (i == 1 && k < d.size() - 1) { d.remove(k); d.add(k + 1, a.pkg); }
            else if (i == 2) d.remove(k);
            else if (i == 3) { startActivity(new Intent(this, AppsActivity.class)); return; }
            Config.setDock(this, d);
            refreshDock();
            for (int j = 0; j < dockRow.getChildCount(); j++) {
                View v = dockRow.getChildAt(j);
                if (v instanceof TileView && ((TileView) v).app.pkg.equals(a.pkg)) v.requestFocus();
            }
        });
    }

    private View page2() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setClipChildren(false);
        p.setClipToPadding(false);
        p.setAlpha(0f);

        LinearLayout groups = new LinearLayout(this);
        groups.setClipChildren(false);
        groups.setClipToPadding(false);
        List<TvInputInfo> ins = inputs();
        if (!ins.isEmpty()) {
            LinearLayout g = group("Входы");
            LinearLayout r = (LinearLayout) g.getChildAt(1);
            for (TvInputInfo info : ins) {
                CharSequence label = info.loadLabel(this);
                LinearLayout pill = Bits.pill(this, getDrawable(info.getType() == TvInputInfo.TYPE_TUNER ? R.drawable.ic_tv : R.drawable.ic_input), label, dp(112), dp(78));
                pill.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))));
                addPill(r, pill);
            }
            groups.addView(g);
        }
        LinearLayout g = group("Ярлыки");
        LinearLayout r = (LinearLayout) g.getChildAt(1);
        LinearLayout all = Bits.pill(this, getDrawable(R.drawable.ic_apps), "Все приложения", dp(140), dp(78));
        all.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        addPill(r, all);
        LinearLayout set = Bits.pill(this, getDrawable(R.drawable.ic_settings), "Настройки", dp(140), dp(78));
        set.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        addPill(r, set);
        LinearLayout wifi = Bits.pill(this, getDrawable(R.drawable.ic_wifi), "Wi-Fi", dp(140), dp(78));
        wifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addPill(r, wifi);
        LinearLayout bt = Bits.pill(this, getDrawable(R.drawable.ic_bluetooth), "Bluetooth", dp(140), dp(78));
        bt.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        addPill(r, bt);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (groups.getChildCount() > 0) gl.leftMargin = dp(28);
        groups.addView(g, gl);
        p.addView(groups);

        ratesBox = card(true);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128));
        rl.topMargin = dp(14);
        p.addView(ratesBox, rl);
        return p;
    }

    private LinearLayout group(String title) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        TextView t = Ui.text(this, 10, R.color.text2, Ui.MEDIUM);
        t.setText(title);
        g.addView(t);
        LinearLayout r = new LinearLayout(this);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        g.addView(r, lp);
        return g;
    }

    private void addPill(LinearLayout r, View pill) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pill.getLayoutParams());
        if (r.getChildCount() > 0) lp.leftMargin = dp(10);
        r.addView(pill, lp);
    }

    private LinearLayout card(boolean focusable) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        float r = dp(22);
        c.setBackground(Ui.round(getColor(R.color.card), r, dp(1), 0xE6FFFFFF));
        c.setPadding(dp(18), dp(15), dp(18), dp(14));
        if (!focusable) return c;
        c.setFocusable(true);
        c.setOnFocusChangeListener((v, f) -> {
            v.setBackground(Ui.round(f ? Color.WHITE : getColor(R.color.card), r, dp(1), 0xE6FFFFFF));
            Ui.focusScale(v, f, 1.025f, dp(10));
        });
        return c;
    }

    // ---- Данные ------------------------------------------------------------

    private void loadData() {
        loadedAt = System.currentTimeMillis();
        Weather.load(this, this::showWeather);
        Rates.load(this, r -> { lastRates = r; showRates(r); updateTicker(); });
    }

    private void showWeather(Weather.Data d) {
        weatherBox.removeAllViews();
        if (d == null) {
            TextView city = Ui.text(this, 10, R.color.text2, Ui.MEDIUM);
            city.setText(Config.city(this));
            weatherBox.addView(city);
            TextView t = Ui.text(this, 12, R.color.text2, Ui.REGULAR);
            t.setText("Погода не загрузилась. Попробуем снова через полчаса.");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            weatherBox.addView(t, lp);
            return;
        }
        weatherBox.setOrientation(LinearLayout.HORIZONTAL);

        // Слева — сейчас: город, значок и температура, описание, макс/мин, восход/закат.
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        TextView city = Ui.text(this, 10, R.color.text2, Ui.MEDIUM);
        city.setText(Config.city(this));
        now.addView(city);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new WeatherIcon(Weather.kind(d.code, d.day)));
        tr.addView(ic, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView temp = Ui.text(this, 46, R.color.text, Ui.LIGHT);
        temp.setText(d.temp + "°");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(10);
        tr.addView(temp, tl);
        LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trl.topMargin = dp(12);
        now.addView(tr, trl);
        TextView desc = Ui.text(this, 12, R.color.text, Ui.MEDIUM);
        desc.setText(Weather.describe(d.code));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(10);
        now.addView(desc, dl);
        TextView mm = Ui.text(this, 10, R.color.text2, Ui.REGULAR);
        mm.setText("За сутки: от " + d.min + "° до " + d.max + "°");
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(5);
        now.addView(mm, ml);
        now.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        TextView sun = Ui.text(this, 9, R.color.text3, Ui.REGULAR);
        sun.setText("Восход " + d.sunrise + "  ·  Закат " + d.sunset);
        now.addView(sun);
        weatherBox.addView(now, new LinearLayout.LayoutParams(dp(262), ViewGroup.LayoutParams.MATCH_PARENT));

        View line = new View(this);
        line.setBackgroundColor(0x1A000000);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT);
        ll.leftMargin = dp(10);
        ll.rightMargin = dp(18);
        weatherBox.addView(line, ll);

        // Справа — по часам и на следующие дни.
        LinearLayout ahead = new LinearLayout(this);
        ahead.setOrientation(LinearLayout.VERTICAL);
        ahead.addView(caption("По часам"));
        LinearLayout hours = new LinearLayout(this);
        for (Weather.Hour h : d.hours) hours.addView(forecast(h.time, h.code, h.day, h.temp + "°"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(8);
        ahead.addView(hours, hl);
        if (!d.days.isEmpty()) {
            ahead.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
            ahead.addView(caption("Дальше"));
            LinearLayout days = new LinearLayout(this);
            for (Weather.Day x : d.days) days.addView(forecast(x.name, x.code, true, x.max + "° / " + x.min + "°"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams yl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            yl.topMargin = dp(8);
            ahead.addView(days, yl);
        }
        weatherBox.addView(ahead, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private TextView caption(String s) {
        TextView t = Ui.text(this, 9, R.color.text3, Ui.MEDIUM);
        t.setText(s);
        return t;
    }

    /** Столбик прогноза: подпись, значок, температура. */
    private View forecast(String label, int code, boolean day, String temp) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView ht = Ui.text(this, 9.5f, R.color.text2, Ui.REGULAR);
        ht.setText(label);
        ht.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(ht);
        ImageView hi = new ImageView(this);
        hi.setImageDrawable(new WeatherIcon(Weather.kind(code, day)));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(26), dp(26));
        il.topMargin = dp(6);
        col.addView(hi, il);
        TextView tt = Ui.text(this, 11.5f, R.color.text, Ui.MEDIUM);
        tt.setText(temp);
        LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ttl.topMargin = dp(6);
        col.addView(tt, ttl);
        return col;
    }

    private void showRates(List<Rates.Rate> rs) {
        ratesBox.removeAllViews();
        FrameLayout head = new FrameLayout(this);
        TextView t = Ui.text(this, 10, R.color.text2, Ui.MEDIUM);
        t.setText("Курсы");
        head.addView(t);
        TextView s = Ui.text(this, 9, R.color.text3, Ui.REGULAR);
        s.setText("за сутки");
        head.addView(s, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        ratesBox.addView(head);
        if (rs.isEmpty()) {
            TextView e = Ui.text(this, 12, R.color.text2, Ui.REGULAR);
            e.setText("Курсы не загрузились");
            ratesBox.addView(e);
            return;
        }
        LinearLayout cols = new LinearLayout(this);
        for (Rates.Rate r : rs) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView n = Ui.text(this, 10, R.color.text2, Ui.REGULAR);
            n.setText(r.sign + "  " + r.name);
            // Высота строки задана: знак ₿ выше обычных букв и иначе сдвигает свой столбик.
            n.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));
            TextView v = Ui.text(this, 13, R.color.text, Ui.MEDIUM);
            v.setText(Rates.money(r.value));
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vl.topMargin = dp(6);
            col.addView(v, vl);
            // Меньше 0,05% — «0,0%», серым: красный или зелёный тут вводит в заблуждение.
            int dir = Math.abs(r.change) < 0.05 ? 0 : r.change > 0 ? 1 : -1;
            TextView ch = Ui.text(this, 8.5f, dir > 0 ? R.color.up : dir < 0 ? R.color.down : R.color.text2, Ui.MEDIUM);
            ch.setText(dir == 0 ? "0,0%" : String.format(new Locale("ru", "RU"), "%+.1f%%", r.change));
            ch.setBackground(Ui.round(dir > 0 ? getColor(R.color.up_bg) : dir < 0 ? getColor(R.color.down_bg) : 0x14000000, dp(7)));
            ch.setPadding(dp(5), dp(2), dp(5), dp(2));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = dp(6);
            col.addView(ch, cl);
            cols.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(10);
        ratesBox.addView(cols, sl);
    }

    private void updateTicker() {
        Date now = new Date();
        clockText.setText(new SimpleDateFormat("HH:mm", Locale.US).format(now));
        String day = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru", "RU")).format(now);
        dateText.setText(Character.toUpperCase(day.charAt(0)) + day.substring(1));
        StringBuilder sb = new StringBuilder();
        for (Rates.Rate r : lastRates) sb.append(sb.length() > 0 ? "     " : "").append(Rates.brief(r));
        ticker.setText(sb);
    }

    // ---- Поведение ---------------------------------------------------------

    private void shift(boolean down) {
        if (down == shifted) return;
        shifted = down;
        float dy = down ? -(dockPanel.getTop() - dp(16)) : 0;
        canvas.animate().translationY(dy).setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        page1.animate().alpha(down ? 0f : 1f).setDuration(260).start();
        page2.animate().alpha(down ? 1f : 0f).setDuration(down ? 380 : 200).start();
    }

    private static boolean isInside(View v, View parent) {
        for (View x = v; x != null; x = x.getParent() instanceof View ? (View) x.getParent() : null)
            if (x == parent) return true;
        return false;
    }

    private void focusFirst(View group) {
        ArrayList<View> fs = new ArrayList<>();
        group.addFocusables(fs, View.FOCUS_DOWN);
        if (!fs.isEmpty()) fs.get(0).requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home на главном экране: вернуться наверх, фокус на первое приложение.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            if (dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
            shift(false);
        }
    }

    @Override public void onBackPressed() {
        if (shifted && dockRow.getChildCount() > 0) dockRow.getChildAt(0).requestFocus();
        // Иначе ничего: с домашнего экрана назад некуда.
    }

    private List<TvInputInfo> inputs() {
        List<TvInputInfo> out = new ArrayList<>();
        TvInputManager m = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        if (m == null) return out;
        try {
            // Только настоящие входы: HDMI и т.п. (passthrough) и эфирный тюнер от системного
            // приложения. Службы предпросмотра VLC и VK тоже числятся «тюнерами» — их не берём.
            for (TvInputInfo i : m.getTvInputList()) {
                boolean system = (i.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (i.isPassthroughInput() || (i.getType() == TvInputInfo.TYPE_TUNER && system)) out.add(i);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void watchNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { Net.MAIN.post(() -> wifiBtn.setImageResource(R.drawable.ic_wifi)); }
            @Override public void onLost(Network n) { Net.MAIN.post(() -> wifiBtn.setImageResource(R.drawable.ic_wifi_off)); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                boolean ok = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                Net.MAIN.post(() -> {
                    wifiBtn.setImageResource(ok ? R.drawable.ic_wifi : R.drawable.ic_wifi_off);
                    if (ok && System.currentTimeMillis() - loadedAt > 60_000 && lastRates.isEmpty()) loadData();
                });
            }
        });
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("Не открывается на этом устройстве"); }
    }

    private void toast(CharSequence s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
