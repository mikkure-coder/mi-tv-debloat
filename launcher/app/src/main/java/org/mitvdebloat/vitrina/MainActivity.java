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
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
 *   1) «Для вас»: вкладки, полка постеров Кинопоиска, ряд приложений;
 *   2) ниже ряда: входы (на ТВ), ярлыки, погода и курсы.
 * Когда фокус уходит ниже ряда приложений, полотно уезжает вверх, и ряд
 * приложений становится шапкой второй страницы — как в видео.
 */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    // Сетка экрана 960×540 dp (приставка и ТВ: 1920×1080 при плотности 320).
    private static final int SIDE = 48, IMG_H = 186, POSTER_W = 124, WIDE_W = 330, GAP = 18;
    private static final int TILE_W = 104, TILE_H = 58, TILE_GAP = 14;

    private LinearLayout canvas, row, dockRow, tabsRow, page1;
    private View dockPanel, page2;
    private ShelfScroll shelf;
    private TextView ticker;
    private ImageView wifiBtn;
    private LinearLayout weatherBox, ratesBox;
    private final List<TextView> tabs = new ArrayList<>();
    private int tab = 0, selected = 0;
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
        canvas.setPadding(dp(SIDE), dp(26), dp(SIDE), 0);
        root.addView(canvas, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1100)));

        page1 = new LinearLayout(this);
        page1.setOrientation(LinearLayout.VERTICAL);
        page1.setClipChildren(false);
        page1.setClipToPadding(false);
        page1.addView(header(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));
        shelf = new ShelfScroll(this);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(0, dp(8), dp(SIDE + 400), 0);
        shelf.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(IMG_H + 52));
        sl.topMargin = dp(8);
        sl.leftMargin = -dp(SIDE);  // полка уходит за левый край, как в видео при прокрутке
        sl.rightMargin = -dp(SIDE);
        shelf.setPadding(dp(SIDE), 0, 0, 0);
        page1.addView(shelf, sl);
        canvas.addView(page1);

        dockPanel = dock();
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TILE_H + 30));
        dl.topMargin = dp(22);
        canvas.addView(dockPanel, dl);

        page2 = page2();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(30);
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
        left.setClipChildren(false);
        left.setClipToPadding(false);
        TextView title = Ui.text(this, 27, R.color.text, Ui.MEDIUM);
        title.setText("Для вас");
        left.addView(title);
        tabsRow = new LinearLayout(this);
        tabsRow.setClipChildren(false);
        tabsRow.setClipToPadding(false);
        for (int i = 0; i < Kino.TABS.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, 11, R.color.text2, Ui.MEDIUM);
            t.setText(Kino.TABS[i]);
            t.setPadding(dp(10), dp(5), dp(10), dp(5));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, f) -> {
                if (f && tab != idx) { tab = idx; styleTabs(); loadShelf(); }
                styleTabs();
            });
            t.setOnClickListener(v -> focusShelf());
            tabs.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i == 0) lp.leftMargin = -dp(10);
            lp.rightMargin = dp(2);
            tabsRow.addView(t, lp);
        }
        styleTabs();
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(8);
        left.addView(tabsRow, tl);
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

    private void styleTabs() {
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            boolean f = t.hasFocus() || t.isFocused();
            t.setTextColor(getColor(i == tab ? R.color.text : R.color.text3));
            t.setBackground(f ? Ui.round(Color.WHITE, dp(14)) : i == tab ? Ui.round(0x80FFFFFF, dp(14)) : null);
            t.setElevation(f ? dp(4) : 0);
        }
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
                LinearLayout pill = Bits.pill(this, getDrawable(info.getType() == TvInputInfo.TYPE_TUNER ? R.drawable.ic_tv : R.drawable.ic_input), label, dp(80), dp(58));
                pill.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()))));
                addPill(r, pill);
            }
            groups.addView(g);
        }
        LinearLayout g = group("Ярлыки");
        LinearLayout r = (LinearLayout) g.getChildAt(1);
        LinearLayout all = Bits.pill(this, getDrawable(R.drawable.ic_apps), "Все приложения", dp(92), dp(58));
        all.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        addPill(r, all);
        LinearLayout set = Bits.pill(this, getDrawable(R.drawable.ic_settings), "Настройки", dp(92), dp(58));
        set.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        addPill(r, set);
        LinearLayout wifi = Bits.pill(this, getDrawable(R.drawable.ic_wifi), "Wi-Fi", dp(92), dp(58));
        wifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addPill(r, wifi);
        LinearLayout bt = Bits.pill(this, getDrawable(R.drawable.ic_bluetooth), "Bluetooth", dp(92), dp(58));
        bt.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        addPill(r, bt);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (groups.getChildCount() > 0) gl.leftMargin = dp(28);
        groups.addView(g, gl);
        p.addView(groups);

        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        weatherBox = card();
        ratesBox = card();
        TextView wait = Ui.text(this, 10, R.color.text3, Ui.REGULAR);
        wait.setText("Загружаю погоду…");
        weatherBox.addView(wait);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, dp(196), 1.45f);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, dp(196), 1f);
        rl.leftMargin = dp(16);
        cards.addView(weatherBox, wl);
        cards.addView(ratesBox, rl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(22);
        p.addView(cards, cl);
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

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        float r = dp(22);
        c.setBackground(Ui.round(getColor(R.color.card), r, dp(1), 0xE6FFFFFF));
        c.setPadding(dp(18), dp(15), dp(18), dp(14));
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
        loadShelf();
        Weather.load(this, this::showWeather);
        Rates.load(this, r -> { lastRates = r; showRates(r); updateTicker(); });
    }

    private void loadShelf() {
        final int forTab = tab;
        Kino.load(this, forTab, (items, err) -> {
            if (forTab != tab) return;
            boolean shelfFocused = row.hasFocus();
            row.removeAllViews();
            selected = 0;
            if (items.isEmpty()) { row.addView(placeholder(err)); return; }
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                Kino.Item it = items.get(i);
                PosterCard pc = new PosterCard(this, it, dp(POSTER_W), dp(WIDE_W), dp(IMG_H));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                if (i > 0) lp.leftMargin = dp(GAP);
                pc.setOnFocusChangeListener(chain(pc.getOnFocusChangeListener(), (v, f) -> { if (f) select(idx); }));
                pc.setOnClickListener(v -> openFilm(it));
                row.addView(pc, lp);
                pc.setWide(i == 0, false);
            }
            shelf.scrollTo(0, 0);
            if (shelfFocused) row.getChildAt(0).requestFocus();
        });
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, f) -> { if (a != null) a.onFocusChange(v, f); b.onFocusChange(v, f); };
    }

    private void select(int idx) {
        if (idx == selected && row.getChildAt(idx) instanceof PosterCard && ((PosterCard) row.getChildAt(idx)).frame().getLayoutParams().width == dp(WIDE_W)) {
            shelf.smoothScrollTo(idx * (dp(POSTER_W) + dp(GAP)), 0);
            return;
        }
        View old = row.getChildAt(selected);
        if (old instanceof PosterCard) ((PosterCard) old).setWide(false, true);
        selected = idx;
        View now = row.getChildAt(idx);
        if (now instanceof PosterCard) ((PosterCard) now).setWide(true, true);
        shelf.smoothScrollTo(idx * (dp(POSTER_W) + dp(GAP)), 0);
    }

    private void focusShelf() {
        View v = row.getChildAt(selected);
        if (v != null && v.isFocusable()) v.requestFocus();
    }

    private View placeholder(String err) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(26), 0, dp(26), 0);
        p.setBackground(Ui.round(0xFFE6EBF2, dp(14), dp(1), 0xE6FFFFFF));
        TextView t = Ui.text(this, 15, R.color.text, Ui.MEDIUM);
        TextView s = Ui.text(this, 10, R.color.text2, Ui.REGULAR);
        s.setSingleLine(false);
        s.setLineSpacing(dp(2), 1f);
        if ("nokey".equals(err)) {
            t.setText("Здесь будут постеры Кинопоиска");
            s.setText("Нужен бесплатный ключ kinopoiskapiunofficial.tech.\nОн ставится с ноутбука скриптом 08 — см. launcher/README.md.");
        } else {
            t.setText("Кинопоиск пока не ответил");
            s.setText("Нет сети или ключ не подошёл. Попробуем снова через полчаса.");
        }
        p.addView(t);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(8);
        p.addView(s, sl);
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(WIDE_W + 2 * (POSTER_W + GAP)), dp(IMG_H)));
        p.setFocusable(true);
        return p;
    }

    private void openFilm(Kino.Item it) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("kpatv://film/" + it.id));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(getPackageManager()) != null) { open(i); return; }
        if (!Apps.launch(this, "ru.kinopoisk.tv")) toast("Нет приложения Кинопоиск");
    }

    private void showWeather(Weather.Data d) {
        weatherBox.removeAllViews();
        TextView city = Ui.text(this, 10, R.color.text2, Ui.MEDIUM);
        city.setText(Config.city(this));
        weatherBox.addView(city);
        if (d == null) {
            TextView t = Ui.text(this, 12, R.color.text2, Ui.REGULAR);
            t.setText("Погода не загрузилась");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            weatherBox.addView(t, lp);
            return;
        }
        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout now = new LinearLayout(this);
        now.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tr = new LinearLayout(this);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new WeatherIcon(Weather.kind(d.code, d.day)));
        tr.addView(ic, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView temp = Ui.text(this, 34, R.color.text, Ui.LIGHT);
        temp.setText(d.temp + "°");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(8);
        tr.addView(temp, tl);
        now.addView(tr);
        TextView desc = Ui.text(this, 10.5f, R.color.text, Ui.MEDIUM);
        desc.setText(Weather.describe(d.code));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = dp(6);
        now.addView(desc, dl);
        TextView mm = Ui.text(this, 9.5f, R.color.text2, Ui.REGULAR);
        mm.setText("Макс " + d.max + "° · Мин " + d.min + "°");
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(3);
        now.addView(mm, ml);
        body.addView(now, new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hours = new LinearLayout(this);
        for (Weather.Hour h : d.hours) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView ht = Ui.text(this, 9, R.color.text2, Ui.REGULAR);
            ht.setText(h.time);
            col.addView(ht);
            ImageView hi = new ImageView(this);
            hi.setImageDrawable(new WeatherIcon(Weather.kind(h.code, h.day)));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(22), dp(22));
            il.topMargin = dp(6);
            col.addView(hi, il);
            TextView tt = Ui.text(this, 11, R.color.text, Ui.MEDIUM);
            tt.setText(h.temp + "°");
            LinearLayout.LayoutParams ttl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ttl.topMargin = dp(6);
            col.addView(tt, ttl);
            hours.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        body.addView(hours, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bl.topMargin = dp(6);
        weatherBox.addView(body, bl);
        TextView sun = Ui.text(this, 9, R.color.text3, Ui.REGULAR);
        sun.setText("Восход " + d.sunrise + "   ·   Закат " + d.sunset);
        weatherBox.addView(sun);
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
        for (Rates.Rate r : rs) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = Ui.text(this, 10.5f, R.color.text, Ui.REGULAR);
            n.setText(r.sign + "  " + r.name);
            line.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = Ui.text(this, 10.5f, R.color.text, Ui.MEDIUM);
            v.setText(Rates.money(r.value));
            line.addView(v);
            TextView ch = Ui.text(this, 8.5f, r.change >= 0 ? R.color.up : R.color.down, Ui.MEDIUM);
            ch.setText(String.format(new Locale("ru", "RU"), "%+.1f%%", r.change));
            ch.setGravity(Gravity.CENTER);
            ch.setBackground(Ui.round(getColor(r.change >= 0 ? R.color.up_bg : R.color.down_bg), dp(7)));
            ch.setPadding(dp(5), dp(2), dp(5), dp(2));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.leftMargin = dp(10);
            line.addView(ch, cl);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            ratesBox.addView(line, ll);
        }
    }

    private void updateTicker() {
        StringBuilder sb = new StringBuilder(new SimpleDateFormat("HH:mm  ·  EE, d MMMM", new Locale("ru", "RU")).format(new Date()));
        for (Rates.Rate r : lastRates) sb.append("     ").append(Rates.brief(r));
        ticker.setText(sb);
    }

    // ---- Поведение ---------------------------------------------------------

    private void shift(boolean down) {
        if (down == shifted) return;
        shifted = down;
        float dy = down ? -(dockPanel.getTop() - dp(22)) : 0;
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

    /** Горизонтальная прокрутка без собственной «докрутки к фокусу»: полку двигаем сами. */
    static final class ShelfScroll extends HorizontalScrollView {
        ShelfScroll(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
