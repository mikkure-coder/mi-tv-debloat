package org.mitvdebloat.vitrina;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

/** Мелочи вёрстки: размеры в dp, текст, скруглённые фоны. */
final class Ui {
    private Ui() {}

    static final float FONT = 1.25f;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static TextView text(Context c, float sp, int colorRes, Typeface tf) {
        TextView t = new TextView(c);
        // Все размеры текста крупнее на четверть: на ТВ смотрят с дивана (просьба владельца).
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * FONT);
        t.setTextColor(c.getColor(colorRes));
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable round(int color, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** Обрезать содержимое вида по скруглённому прямоугольнику. */
    static void clipRound(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    /** Плавное увеличение при фокусе — как на Apple TV. */
    static void focusScale(View v, boolean focused, float scale, float elevationPx) {
        // Мягкая тень на светлом фоне: тёмная по умолчанию выглядит грязным пятном.
        v.setOutlineSpotShadowColor(0x55303A4A);
        v.setOutlineAmbientShadowColor(0x33303A4A);
        v.animate().scaleX(focused ? scale : 1f).scaleY(focused ? scale : 1f)
                .translationZ(focused ? elevationPx : 0f)
                .setDuration(focused ? 170 : 140)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }
}
