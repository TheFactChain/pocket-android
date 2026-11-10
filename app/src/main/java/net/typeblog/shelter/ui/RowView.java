package net.typeblog.shelter.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import net.typeblog.shelter.R;

/** A tappable settings row from Pocket Windows: coloured 30dp tile, title, caption and an optional end view. */
public class RowView extends LinearLayout {
    private final FrameLayout tile;
    private final ImageView icon;
    private final TextView title, caption;
    private final FrameLayout end;

    public RowView(Context context) { this(context, null); }

    public RowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(56));
        setPadding(dp(16), dp(8), dp(12), dp(8));
        setBackgroundResource(R.drawable.bg_row);
        tile = new FrameLayout(context);
        tile.setBackgroundResource(R.drawable.bg_tile);
        icon = new ImageView(context);
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        tile.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        LayoutParams tileParams = new LayoutParams(dp(30), dp(30));
        tileParams.setMarginEnd(dp(14));
        addView(tile, tileParams);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextAppearance(R.style.RowText);
        caption = new TextView(context);
        caption.setTextAppearance(R.style.Caption);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(caption);
        addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        end = new FrameLayout(context);
        LayoutParams endParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        endParams.setMarginStart(dp(8));
        addView(end, endParams);
        end.setVisibility(GONE);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.RowView);
        try {
            int iconRes = a.getResourceId(R.styleable.RowView_rowIcon, 0);
            if (iconRes != 0) icon.setImageResource(iconRes);
            else tile.setVisibility(GONE);
            if (a.getBoolean(R.styleable.RowView_rowRound, false)) tile.setBackgroundResource(R.drawable.bg_circle);
            setTile(a.getColor(R.styleable.RowView_rowTile, ContextCompat.getColor(context, R.color.accent)));
            setTitle(a.getString(R.styleable.RowView_rowTitle));
            setCaption(a.getString(R.styleable.RowView_rowCaption));
            if (a.hasValue(R.styleable.RowView_rowTitleColor)) title.setTextColor(a.getColor(R.styleable.RowView_rowTitleColor, 0));
        } finally {
            a.recycle();
        }
        setClickable(true);
        setFocusable(true);
    }

    public RowView setTitle(CharSequence text) { title.setText(text); return this; }

    public RowView setTitleBold(boolean bold) { title.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL); return this; }

    public RowView setTitleColor(int color) { title.setTextColor(color); return this; }

    public RowView setCaption(CharSequence text) {
        caption.setText(text);
        caption.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
        return this;
    }

    public RowView setTile(int color) { tile.setBackgroundTintList(ColorStateList.valueOf(color)); return this; }

    public RowView setIcon(int res, int tileColor, int iconColor) {
        tile.setVisibility(VISIBLE);
        icon.setImageResource(res);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        setTile(tileColor);
        return this;
    }

    /** An application icon fills the tile instead of a glyph. */
    public RowView setAppIcon(Drawable drawable) {
        tile.setVisibility(VISIBLE);
        tile.setBackground(null);
        icon.setImageTintList(null);
        icon.setImageDrawable(drawable);
        icon.setLayoutParams(new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        return this;
    }

    public RowView setAppIcon(Bitmap bitmap) {
        return setAppIcon(new android.graphics.drawable.BitmapDrawable(getResources(), bitmap));
    }

    public RowView setEnd(View view) {
        end.removeAllViews();
        if (view != null) end.addView(view);
        end.setVisibility(view == null ? GONE : VISIBLE);
        return this;
    }

    /** A status capsule on the right, like «Доступно» or «900 ₽». */
    public TextView setPill(CharSequence text, int background, int foreground) {
        TextView pill = pill(getContext(), text, background, foreground);
        setEnd(pill);
        return pill;
    }

    static TextView pill(Context context, CharSequence text, int background, int foreground) {
        TextView pill = new TextView(context);
        pill.setTextAppearance(R.style.StatusPill);
        pill.setBackgroundResource(R.drawable.bg_capsule);
        int h = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, context.getResources().getDisplayMetrics());
        int v = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, context.getResources().getDisplayMetrics());
        pill.setPadding(h, v, h, v);
        pill.setMaxLines(1);
        pill.setBackgroundTintList(ColorStateList.valueOf(background));
        pill.setTextColor(foreground);
        pill.setText(text);
        return pill;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
