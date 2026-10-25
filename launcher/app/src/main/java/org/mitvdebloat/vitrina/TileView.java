package org.mitvdebloat.vitrina;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Плитка приложения: баннер 16:9, а если его нет — значок и название на белом. */
final class TileView extends FrameLayout {
    final Apps.App app;
    private final TextView check;

    TileView(Context c, Apps.App app, int w, int h) {
        super(c);
        this.app = app;
        float r = Ui.dp(c, 12);
        setLayoutParams(new LayoutParams(w, h));
        setBackground(Ui.round(Color.WHITE, r));
        Ui.clipRound(this, r);
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);

        if (app.banner != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(app.banner);
            addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(app.icon);
            int s = Math.round(h * .46f);
            col.addView(iv, new LinearLayout.LayoutParams(s, s));
            TextView t = Ui.text(c, Math.max(8f, h / 7.5f / c.getResources().getDisplayMetrics().density * 1.0f), R.color.text, Ui.MEDIUM);
            t.setText(app.label);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w - Ui.dp(c, 8), LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 3);
            col.addView(t, lp);
            addView(col, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }

        // Галочка «на главном экране» — нужна в «Всех приложениях».
        check = Ui.text(c, 9, R.color.text, Ui.MEDIUM);
        check.setText("✓");
        check.setTextColor(Color.WHITE);
        check.setGravity(Gravity.CENTER);
        check.setBackground(Ui.round(0xFF1D1D1F, Ui.dp(c, 8)));
        LayoutParams cl = new LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16), Gravity.TOP | Gravity.END);
        cl.setMargins(0, Ui.dp(c, 5), Ui.dp(c, 5), 0);
        check.setVisibility(GONE);
        addView(check, cl);

        final int ring = Ui.dp(c, 2);
        setOnFocusChangeListener((v, f) -> {
            Ui.focusScale(this, f, 1.13f, Ui.dp(c, 14));
            setForeground(f ? Ui.round(Color.TRANSPARENT, r, ring, Color.WHITE) : null);
        });
    }

    void setChecked(boolean on) { check.setVisibility(on ? VISIBLE : GONE); }
}
