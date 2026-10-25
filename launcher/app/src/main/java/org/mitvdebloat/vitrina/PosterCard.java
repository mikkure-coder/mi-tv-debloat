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
    private final ImageView image, front;
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
        // Постер целиком поверх размытого фона — когда горизонтальной обложки нет.
        front = new ImageView(c);
        front.setScaleType(ImageView.ScaleType.FIT_CENTER);
        front.setVisibility(GONE);
        front.setElevation(Ui.dp(c, 6));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int m = Ui.dp(c, 10);
        fl.setMargins(m, m, m, m);
        frame.addView(front, fl);
        addView(frame, new LayoutParams(narrowW, imgH));

        title = Ui.text(c, 11.5f, R.color.text, Ui.MEDIUM);
        title.setText(item.title);
        LayoutParams tl = new LayoutParams(narrowW, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 8);
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
        boolean hasCover = item.cover != null && !item.cover.isEmpty() && !item.cover.equals(item.poster);
        if (!wide) {
            plain();
            Images.load(getContext(), item.poster, narrowW, imgH, image);
        } else if (hasCover) {
            plain();
            // Обложка бывает и вертикальной (у демо — всегда): тогда показываем её целиком.
            Images.load(getContext(), item.cover, wideW, imgH, image, b -> {
                if (wide && b.getHeight() * 1.2f > b.getWidth()) posterOnBlur(item.cover);
            });
        } else {
            posterOnBlur(item.poster);
        }
    }

    private void plain() {
        front.setVisibility(GONE);
        front.setImageDrawable(null);
        image.setScaleX(1f);
        image.setScaleY(1f);
        if (android.os.Build.VERSION.SDK_INT >= 31) image.setRenderEffect(null);
        image.clearColorFilter();
    }

    /** Широкая карточка из вертикального постера: размытый фон + постер целиком, ничего не режем. */
    private void posterOnBlur(String url) {
        // Фон — тот же постер, декодированный крошечным и растянутым: это уже размытие,
        // а на Android 12+ его сглаживает RenderEffect.
        Images.load(getContext(), url, 24, 36, image);
        image.setScaleX(1.25f);
        image.setScaleY(1.25f);
        if (android.os.Build.VERSION.SDK_INT >= 31)
            image.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    Ui.dp(getContext(), 14), Ui.dp(getContext(), 14), android.graphics.Shader.TileMode.CLAMP));
        image.setColorFilter(0x26000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        front.setVisibility(VISIBLE);
        Images.load(getContext(), url, wideW, imgH, front);
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
