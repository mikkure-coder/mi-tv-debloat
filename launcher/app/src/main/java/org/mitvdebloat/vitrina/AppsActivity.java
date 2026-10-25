package org.mitvdebloat.vitrina;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Все приложения сеткой. OK — открыть; удержать OK (или ☰) — добавить на главный
 * экран или убрать с него. Галочка в углу — приложение уже в ряду.
 */
public class AppsActivity extends Activity {
    private static final int COLS = 6, W = 130, H = 73;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(48), dp(30), dp(48), dp(40));
        sv.addView(col);

        TextView title = Ui.text(this, 25, R.color.text, Ui.MEDIUM);
        title.setText("Все приложения");
        col.addView(title);
        TextView hint = Ui.text(this, 10, R.color.text2, Ui.REGULAR);
        hint.setText("OK — открыть     ·     удержите OK — добавить на главный экран или убрать");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(8);
        hl.bottomMargin = dp(14);
        col.addView(hint, hl);

        List<Apps.App> apps = Apps.all(this);
        List<String> dock = Config.dock(this);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.topMargin = dp(14);
                col.addView(row, rl);
            }
            Apps.App a = apps.get(i);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            cell.setClipToPadding(false);
            TileView t = new TileView(this, a, dp(W), dp(H));
            t.setChecked(dock.contains(a.pkg));
            t.setOnClickListener(v -> { if (!Apps.launch(this, a.pkg)) Toast.makeText(this, "Не запускается", Toast.LENGTH_SHORT).show(); });
            t.setOnLongClickListener(v -> { toggle(a, t); return true; });
            t.setOnKeyListener((v, code, e) -> {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) { toggle(a, t); return true; }
                return false;
            });
            cell.addView(t, new LinearLayout.LayoutParams(dp(W), dp(H)));
            TextView name = Ui.text(this, 9.5f, R.color.text2, Ui.REGULAR);
            name.setText(a.label);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(W), ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = dp(7);
            cell.addView(name, nl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i % COLS != 0) cl.leftMargin = dp(16);
            row.addView(cell, cl);
            if (i == 0) t.post(t::requestFocus);
        }
        setContentView(sv);
    }

    private void toggle(Apps.App a, TileView t) {
        List<String> d = Config.dock(this);
        boolean on = d.contains(a.pkg);
        Bits.menu(this, a.label, new String[]{on ? "Убрать с главного экрана" : "Добавить на главный экран", "Отмена"}, i -> {
            if (i != 0) return;
            List<String> dd = Config.dock(this);
            if (on) dd.remove(a.pkg); else dd.add(a.pkg);
            Config.setDock(this, dd);
            t.setChecked(!on);
        });
    }
}
