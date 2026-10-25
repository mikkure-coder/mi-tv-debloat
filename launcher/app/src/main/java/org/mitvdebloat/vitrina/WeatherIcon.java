package org.mitvdebloat.vitrina;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Значки погоды, нарисованные кодом: солнце, луна, облака, дождь, снег, гроза, туман. */
final class WeatherIcon extends Drawable {
    enum Kind { SUN, MOON, PARTLY_DAY, PARTLY_NIGHT, CLOUD, FOG, RAIN, SNOW, STORM }

    private static final int SUN = 0xFFFFB627, MOON = 0xFF8C96AD, CLOUD = 0xFFAEB8C8,
            CLOUD_EDGE = 0xFF97A2B4, RAIN = 0xFF3D8BE0, SNOW = 0xFF8FB8E6, BOLT = 0xFFFFC21A;

    private final Kind kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    WeatherIcon(Kind kind) { this.kind = kind; }

    @Override public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height());
        c.save();
        c.translate(getBounds().left, getBounds().top);
        switch (kind) {
            case SUN: sun(c, s * .5f, s * .5f, s * .22f); break;
            case MOON: moon(c, s * .5f, s * .5f, s * .3f); break;
            case PARTLY_DAY: sun(c, s * .38f, s * .36f, s * .17f); cloud(c, s, s * .12f); break;
            case PARTLY_NIGHT: moon(c, s * .38f, s * .36f, s * .2f); cloud(c, s, s * .12f); break;
            case CLOUD: cloud(c, s, 0); break;
            case FOG: fog(c, s); break;
            case RAIN: cloud(c, s, -s * .1f); drops(c, s, RAIN, false); break;
            case SNOW: cloud(c, s, -s * .1f); drops(c, s, SNOW, true); break;
            case STORM: cloud(c, s, -s * .1f); bolt(c, s); break;
        }
        c.restore();
    }

    private void sun(Canvas c, float x, float y, float r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(SUN);
        c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * .28f);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            c.drawLine(x + (float) Math.cos(a) * r * 1.45f, y + (float) Math.sin(a) * r * 1.45f,
                    x + (float) Math.cos(a) * r * 1.85f, y + (float) Math.sin(a) * r * 1.85f, p);
        }
    }

    private void moon(Canvas c, float x, float y, float r) {
        Path a = new Path(), b = new Path();
        a.addCircle(x, y, r, Path.Direction.CW);
        b.addCircle(x + r * .55f, y - r * .35f, r * .85f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setColor(MOON);
        c.drawPath(a, p);
    }

    private void cloud(Canvas c, float s, float dy) {
        Path path = new Path();
        float base = s * .72f + dy;
        path.addRoundRect(new RectF(s * .14f, base - s * .2f, s * .88f, base), s * .1f, s * .1f, Path.Direction.CW);
        Path t = new Path();
        t.addCircle(s * .38f, base - s * .2f, s * .16f, Path.Direction.CW);
        path.op(t, Path.Op.UNION);
        t.reset();
        t.addCircle(s * .6f, base - s * .24f, s * .21f, Path.Direction.CW);
        path.op(t, Path.Op.UNION);
        p.setStyle(Paint.Style.FILL);
        p.setColor(CLOUD);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * .025f);
        p.setColor(CLOUD_EDGE);
        c.drawPath(path, p);
    }

    private void drops(Canvas c, float s, int color, boolean snow) {
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float x = s * (.33f + .18f * i), y = s * .74f;
            if (snow) {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y + s * .06f, s * .045f, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(s * .055f);
                c.drawLine(x, y, x - s * .05f, y + s * .14f, p);
            }
        }
    }

    private void bolt(Canvas c, float s) {
        Path b = new Path();
        b.moveTo(s * .54f, s * .6f);
        b.lineTo(s * .4f, s * .8f);
        b.lineTo(s * .5f, s * .8f);
        b.lineTo(s * .44f, s * .96f);
        b.lineTo(s * .62f, s * .72f);
        b.lineTo(s * .52f, s * .72f);
        b.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(BOLT);
        c.drawPath(b, p);
    }

    private void fog(Canvas c, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * .07f);
        p.setColor(CLOUD_EDGE);
        for (int i = 0; i < 3; i++) {
            float y = s * (.35f + .15f * i);
            c.drawLine(s * (.2f + .06f * (i % 2)), y, s * (.8f - .06f * (i % 2)), y, p);
        }
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
