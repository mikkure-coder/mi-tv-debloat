package org.mitvdebloat.vitrina;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Круглые кнопки, ярлыки и меню плитки. */
final class Bits {
    private Bits() {}

    static ImageView roundButton(Context c, int iconRes, int size) {
        ImageView b = new ImageView(c);
        b.setImageResource(iconRes);
        int pad = Math.round(size * .26f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(Ui.round(0xB8FFFFFF, size / 2f, Ui.dp(c, 1), 0xE6FFFFFF));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(Ui.round(f ? Color.WHITE : 0xB8FFFFFF, size / 2f, Ui.dp(c, 1), 0xE6FFFFFF));
            Ui.focusScale(v, f, 1.18f, Ui.dp(c, 10));
        });
        return b;
    }

    /** Ярлык: значок над подписью, как «Все приложения» и «Настройки» в видео. */
    static LinearLayout pill(Context c, Drawable icon, CharSequence label, int w, int h) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER);
        float r = Ui.dp(c, 14);
        p.setBackground(Ui.round(0xB8FFFFFF, r, Ui.dp(c, 1), 0xE6FFFFFF));
        p.setFocusable(true);
        p.setClickable(true);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        int s = Ui.dp(c, 19);
        p.addView(iv, new LinearLayout.LayoutParams(s, s));
        TextView t = Ui.text(c, 9f, R.color.text2, Ui.MEDIUM);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w - Ui.dp(c, 8), LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 6);
        p.addView(t, tl);
        p.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        p.setOnFocusChangeListener((v, f) -> {
            v.setBackground(Ui.round(f ? Color.WHITE : 0xB8FFFFFF, r, Ui.dp(c, 1), 0xE6FFFFFF));
            t.setTextColor(c.getColor(f ? R.color.text : R.color.text2));
            Ui.focusScale(v, f, 1.1f, Ui.dp(c, 10));
        });
        return p;
    }

    interface Pick { void picked(int index); }

    /** Небольшое меню в стиле экрана: заголовок и список действий. */
    static void menu(Context c, CharSequence title, String[] items, Pick pick) {
        Dialog d = new Dialog(c, R.style.VitrinaDialog);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(c, 18);
        box.setPadding(pad, pad, pad, Ui.dp(c, 12));
        box.setBackground(Ui.round(0xF7FFFFFF, Ui.dp(c, 22)));
        TextView t = Ui.text(c, 14, R.color.text, Ui.MEDIUM);
        t.setText(title);
        box.addView(t);
        View gap = new View(c);
        box.addView(gap, new LinearLayout.LayoutParams(1, Ui.dp(c, 10)));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView b = Ui.text(c, 12, R.color.text, Ui.REGULAR);
            b.setText(items[i]);
            b.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? Ui.round(0xFF1D1D1F, Ui.dp(c, 12)) : null);
                ((TextView) v).setTextColor(f ? Color.WHITE : c.getColor(R.color.text));
            });
            b.setOnClickListener(v -> { d.dismiss(); pick.picked(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 240), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 2);
            box.addView(b, lp);
        }
        FrameLayout wrap = new FrameLayout(c);
        wrap.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        d.setContentView(wrap);
        d.show();
    }
}
