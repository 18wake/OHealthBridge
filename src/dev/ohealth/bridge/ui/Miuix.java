package dev.ohealth.bridge.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * HyperOS / MIUI 风格轻量组件库(纯 Java 程序化实现, 无资源依赖, 无 lambda)。
 * 提供配色、圆角卡片、设置行、自绘开关、按钮等。
 */
public final class Miuix {

    /* ---------------- 主题色 ---------------- */

    public static int accent(Context c) {
        return dark(c) ? 0xFF5B9DFF : 0xFF3484FF;
    }

    public static int pageBg(Context c) {
        return dark(c) ? 0xFF0B0B0D : 0xFFF3F4F6;
    }

    public static int cardBg(Context c) {
        return dark(c) ? 0xFF1B1B1E : 0xFFFFFFFF;
    }

    public static int textMain(Context c) {
        return dark(c) ? 0xFFF1F1F4 : 0xFF1B1B1F;
    }

    public static int textSub(Context c) {
        return dark(c) ? 0xFF8E8E96 : 0xFF8A8A92;
    }

    public static int divider(Context c) {
        return dark(c) ? 0xFF2B2B2F : 0xFFF0F0F2;
    }

    public static int trackOff(Context c) {
        return dark(c) ? 0xFF43434A : 0xFFE3E3E8;
    }

    public static int fieldBg(Context c) {
        return dark(c) ? 0xFF26262A : 0xFFF5F5F7;
    }

    public static boolean dark(Context c) {
        int m = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return m == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /* ---------------- 卡片 ---------------- */

    /** 圆角卡片容器(竖向)。行内容请用 row() 添加。 */
    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg(c));
        bg.setCornerRadius(dp(c, 18));
        card.setBackground(bg);
        card.setElevation(dp(c, 1));
        return card;
    }

    /** 小节标题(卡片上方) */
    public static TextView sectionTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(textSub(c));
        t.setPadding(dp(c, 10), dp(c, 16), dp(c, 10), dp(c, 6));
        return t;
    }

    /** 卡片内标准行内边距的横向容器 */
    public static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int h = dp(c, 16);
        row.setPadding(h, dp(c, 13), h, dp(c, 13));
        return row;
    }

    /** 卡片内分隔线 */
    public static void addDivider(Context c, LinearLayout card) {
        View line = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        line.setBackgroundColor(divider(c));
        line.setLayoutParams(lp);
        card.addView(line);
    }

    /** 带标题/副标题的开关行 */
    public static View switchRow(Context c, String title, String sub,
                                 boolean checked, final SwitchRowListener l) {
        LinearLayout row = row(c);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(textMain(c));
        texts.addView(t);
        if (sub != null) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextSize(13);
            s.setTextColor(textSub(c));
            s.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(c);
        sw.setCheckedChangeListener(new Switch.Listener() {
            @Override
            public void onCheckedChanged(Switch view, boolean isChecked) {
                if (l != null) {
                    l.onChanged(isChecked);
                }
            }
        });
        sw.setCheckedImmediate(checked);
        row.addView(sw);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        return row;
    }

    public interface SwitchRowListener {
        void onChanged(boolean checked);
    }

    /** 卡片内的说明文字行 */
    public static TextView noteRow(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(textSub(c));
        t.setLineSpacing(dp(c, 2), 1f);
        int h = dp(c, 16);
        t.setPadding(h, dp(c, 12), h, dp(c, 12));
        return t;
    }

    /* ---------------- 输入框 ---------------- */

    public static EditText edit(Context c, String text) {
        EditText e = new EditText(c);
        e.setText(text);
        e.setTextSize(14);
        e.setTextColor(textMain(c));
        e.setHintTextColor(textSub(c));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fieldBg(c));
        bg.setCornerRadius(dp(c, 12));
        e.setBackground(bg);
        e.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        return e;
    }

    /* ---------------- 按钮 ---------------- */

    /** 主按钮(强调色填充) */
    public static Button primaryButton(Context c, String text) {
        Button b = mkBtn(c, text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(accent(c));
        bg.setCornerRadius(dp(c, 14));
        b.setBackground(bg);
        b.setTextColor(0xFFFFFFFF);
        return b;
    }

    /** 次按钮(浅色底) */
    public static Button button(Context c, String text) {
        Button b = mkBtn(c, text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark(c) ? 0xFF2C2C31 : 0xFFECECF0);
        bg.setCornerRadius(dp(c, 14));
        b.setBackground(bg);
        b.setTextColor(accent(c));
        return b;
    }

    private static Button mkBtn(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        return b;
    }

    /** 双按钮横排 */
    public static LinearLayout buttonPair(Context c, Button first, Button second) {
        LinearLayout pair = new LinearLayout(c);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        int h = dp(c, 16);
        pair.setPadding(h, dp(c, 4), h, dp(c, 12));
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        pair.addView(first, lp0);
        View space = new View(c);
        space.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 10), 1));
        pair.addView(space);
        pair.addView(second, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return pair;
    }

    /* ---------------- 自绘开关 ---------------- */

    /** HyperOS 风格自绘开关(带滑动动画) */
    public static class Switch extends View {

        public interface Listener {
            void onCheckedChanged(Switch view, boolean isChecked);
        }

        private boolean checked = false;
        private float pos = 0f; // 0=off 1=on
        private ValueAnimator anim;
        private Listener listener;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Switch(Context c) {
            super(c);
            int w = dp(c, 48);
            int h = dp(c, 29);
            setLayoutParams(new LinearLayout.LayoutParams(w, h));
            thumbPaint.setColor(0xFFFFFFFF);
            setClickable(true);
        }

        public void setCheckedChangeListener(Listener l) {
            listener = l;
        }

        public boolean isChecked() {
            return checked;
        }

        public void setCheckedImmediate(boolean value) {
            checked = value;
            pos = value ? 1f : 0f;
            invalidate();
        }

        public void setChecked(boolean value, boolean animate) {
            if (checked == value) {
                return;
            }
            checked = value;
            if (anim != null && anim.isRunning()) {
                anim.cancel();
            }
            float from = pos;
            float to = value ? 1f : 0f;
            if (animate) {
                anim = ValueAnimator.ofFloat(from, to);
                anim.setDuration(180);
                anim.setInterpolator(new DecelerateInterpolator());
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator animation) {
                        pos = (Float) animation.getAnimatedValue();
                        invalidate();
                    }
                });
                anim.start();
            } else {
                pos = to;
                invalidate();
            }
            if (listener != null) {
                listener.onCheckedChanged(this, checked);
            }
        }

        public void toggle() {
            setChecked(!checked, true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            int a = accent(c);
            int off = trackOff(c);
            int color = lerp(off, a, pos);
            trackPaint.setColor(color);
            float r = h / 2f;
            canvas.drawRoundRect(0, 0, w, h, r, r, trackPaint);

            float margin = dp(c, 3);
            float d = h - margin * 2;
            float travel = w - d - margin * 2;
            float cx = margin + d / 2f + travel * pos;
            // 开启时轨道内缩, 视觉上包裹滑块
            canvas.drawCircle(cx, h / 2f, d / 2f, thumbPaint);
        }

        private static int lerp(int from, int to, float t) {
            int a = Color.alpha(from) + (int) ((Color.alpha(to) - Color.alpha(from)) * t);
            int r = Color.red(from) + (int) ((Color.red(to) - Color.red(from)) * t);
            int g = Color.green(from) + (int) ((Color.green(to) - Color.green(from)) * t);
            int b = Color.blue(from) + (int) ((Color.blue(to) - Color.blue(from)) * t);
            return Color.argb(a, r, g, b);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float x = event.getX();
                boolean tapRight = x > getWidth() / 2f;
                // 点哪边就切到哪边(MIUI 交互)
                if (tapRight != checked) {
                    setChecked(tapRight, true);
                } else {
                    setChecked(!checked, true);
                }
                performClick();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private Miuix() {
    }
}
