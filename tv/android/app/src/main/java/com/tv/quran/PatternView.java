package com.tv.quran;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** خلفية: تدرّج أخضر غامق + زخرفة نجمة ثُمانية خفيفة + توهّج دهبي ورا العنوان. */
final class PatternView extends View {

    private Bitmap cache;

    PatternView(Context c) {
        super(c);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        if (cache != null) cache.recycle();
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        paint(new Canvas(cache), w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (cache != null) canvas.drawBitmap(cache, 0, 0, null);
    }

    private void paint(Canvas c, int w, int h) {
        float d = getResources().getDisplayMetrics().density;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // التدرّج الأساسي
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF0A1A15, 0xFF0F2A21, 0xFF07130F}, new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // الزخرفة: شبكة نجوم ثُمانية (مربعين فوق بعض، واحد ملفوف 45°)
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f * d);
        p.setColor(0x14D4AF37);
        float cell = 92 * d;
        float r = cell * 0.36f;
        Path star = new Path();
        for (int k = 0; k < 2; k++) {
            double base = k * Math.PI / 4;
            for (int i = 0; i < 4; i++) {
                double a = base + i * Math.PI / 2 + Math.PI / 4;
                float x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r);
                if (i == 0) star.moveTo(x, y);
                else star.lineTo(x, y);
            }
            star.close();
        }
        for (float y = 0; y < h + cell; y += cell) {
            for (float x = 0; x < w + cell; x += cell) {
                c.save();
                c.translate(x, y);
                c.drawPath(star, p);
                c.drawCircle(0, 0, r * 0.32f, p);
                c.restore();
                // خط يوصّل النجوم ببعض
                c.drawLine(x + r, y, x + cell - r, y, p);
                c.drawLine(x, y + r, x, y + cell - r, p);
            }
        }

        // توهّج دهبي ورا العنوان
        p.setStyle(Paint.Style.FILL);
        float cx = w / 2f, cy = h * 0.36f;
        p.setShader(new RadialGradient(cx, cy, Math.max(w, h) * 0.45f,
                new int[]{0x33D4AF37, 0x0FD4AF37, 0x00D4AF37}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // تغميق الأطراف
        p.setShader(new RadialGradient(cx, h / 2f, Math.max(w, h) * 0.75f,
                new int[]{0x00000000, 0x00000000, 0xB3000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }
}
