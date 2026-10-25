package org.mitvdebloat.vitrina;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Карточка полки. Выбранная — широкая (обложка 16:9) с подписью «Фильм · Жанр ·
 * Год · Рейтинг», остальные — узкие постеры 2:3 с названием, как в видео.
 */
final class PosterCard extends LinearLayout {
    final Kino.Item item;
    final int narrowW, wideW, imgH;
    private final FrameLayout frame;
    private final ImageView image;
    private final TextView title, meta;
    private boolean wide;
    private ValueAnimator anim;

    PosterCard(Context c, Kino.Item item, int narrowW, int wideW, int imgH) {
        super(c);
        this.item = item;
        this.narrowW = narrowW;
        this.wideW = wideW;
        this.imgH = imgH;
        setOrientation(VERTICAL);
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);
        setClipToPadding(false);

        float r = Ui.dp(c, 14);
        frame = new FrameLayout(c);
        frame.setBackground(Ui.round(0xFFE1E7EF, r));
        Ui.clipRound(frame, r);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(frame, new LayoutParams(narrowW, imgH));

        title = Ui.text(c, 11.5f, R.color.text, Ui.MEDIUM);
        title.setText(item.title);
        LayoutParams tl = new LayoutParams(narrowW, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 9);
        addView(title, tl);
        meta = Ui.text(c, 9f, R.color.text2, Ui.REGULAR);
        meta.setText(item.meta());
        LayoutParams ml = new LayoutParams(wideW, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 4);
        meta.setVisibility(INVISIBLE);
        addView(meta, ml);
        // Ширину самой карточки задаёт рамка картинки; подпись под широкой не должна раздувать узкую.
        meta.setLayoutParams(ml);

        final int ring = Ui.dp(c, 2);
        setOnFocusChangeListener((v, f) -> {
            frame.setForeground(f ? Ui.round(Color.TRANSPARENT, r, ring, Color.WHITE) : null);
            frame.animate().scaleX(f ? 1.035f : 1f).scaleY(f ? 1.035f : 1f)
                    .translationZ(f ? Ui.dp(c, 16) : 0).setDuration(170).start();
        });
        showImage();
    }

    private void showImage() {
        String url = wide && item.cover != null && !item.cover.isEmpty() ? item.cover : item.poster;
        int w = wide ? wideW : narrowW;
        Images.load(getContext(), url, w, imgH, image);
    }

    void setWide(boolean on, boolean animate) {
        if (wide == on) return;
        wide = on;
        meta.setVisibility(on ? VISIBLE : INVISIBLE);
        int from = frame.getLayoutParams().width, to = on ? wideW : narrowW;
        if (anim != null) anim.cancel();
        if (!animate) { setW(to); showImage(); return; }
        anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(260);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> setW((int) a.getAnimatedValue()));
        anim.start();
        showImage();
    }

    private void setW(int w) {
        frame.getLayoutParams().width = w;
        title.getLayoutParams().width = w;
        frame.requestLayout();
        title.requestLayout();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        // Подпись meta шире узкой карточки: меряем карточку по рамке, а meta пусть выходит вправо.
        super.onMeasure(MeasureSpec.makeMeasureSpec(frame.getLayoutParams().width, MeasureSpec.EXACTLY), hSpec);
    }

    View frame() { return frame; }
}
