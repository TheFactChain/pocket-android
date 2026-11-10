package net.typeblog.shelter.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import net.typeblog.shelter.R;

/** One screen in the page stack. Built once, kept while it is on the stack. */
abstract class Page {
    final MainActivity a;
    private View view;
    LinearLayout content;
    /** A root tab has no «Назад». */
    boolean tab;

    Page(MainActivity activity) { a = activity; }

    final View view() {
        if (view == null) view = create(LayoutInflater.from(a));
        return view;
    }

    /** Default page: the header with «Назад» and a scrolling column. */
    View create(LayoutInflater inflater) {
        View root = inflater.inflate(R.layout.page_scroll, null);
        ((TextView) root.findViewById(R.id.page_title)).setText(title());
        View back = root.findViewById(R.id.page_back);
        if (tab) back.setVisibility(View.GONE);
        else back.setOnClickListener(v -> a.back());
        content = root.findViewById(R.id.page_content);
        build();
        return root;
    }

    String title() { return ""; }

    /** Pages that may show passwords keep them out of screenshots. */
    boolean secure() { return false; }

    /** A sheet with «Закрыть»; without it the sheet closes by a tap outside or a swipe. */
    boolean closeButton() { return true; }

    private com.google.android.material.bottomsheet.BottomSheetDialog sheetDialog;

    boolean showing() { return sheetDialog != null && sheetDialog.isShowing(); }

    /** Every section opens as a sheet over the one home screen; «Закрыть» or Back dismisses it. */
    void showAsSheet() {
        if (showing()) return;
        View root = view();
        root.setBackground(null); // the sheet draws the rounded page background
        if (root.getParent() != null) ((android.view.ViewGroup) root.getParent()).removeView(root);
        TextView back = root.findViewById(R.id.page_back);
        if (back != null && !closeButton()) {
            if (back.getVisibility() == View.GONE) { /* the grabber is already there from an earlier showing */ } else {
            // No button: a tap outside or a swipe down dismisses; the grabber says so.
            back.setVisibility(View.GONE);
            View grabber = new View(a);
            grabber.setBackgroundResource(R.drawable.bg_capsule);
            grabber.setBackgroundTintList(android.content.res.ColorStateList.valueOf(a.color(R.color.hint)));
            android.widget.FrameLayout.LayoutParams gp = new android.widget.FrameLayout.LayoutParams(a.dp(36), a.dp(4),
                    android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            gp.topMargin = a.dp(8);
            ((android.view.ViewGroup) back.getParent()).addView(grabber, gp);
            }
        } else if (back != null) {
            back.setText("Закрыть");
            back.setOnClickListener(v -> sheetDialog.dismiss());
        }
        View scroll = root.findViewById(R.id.page_scroll);
        if (scroll != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) scroll.getLayoutParams();
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            params.weight = 0;
        }
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(a);
        dialog.setContentView(root, new android.view.ViewGroup.LayoutParams(-1, -2));
        if (secure() && dialog.getWindow() != null) dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dialog.getBehavior().setSkipCollapsed(true);
        dialog.setOnDismissListener(d -> { a.sheets.remove(this); onHide(); });
        sheetDialog = dialog;
        a.sheets.add(this);
        dialog.show();
        onShow();
    }

    void dismissSheet() { if (showing()) sheetDialog.dismiss(); }

    void build() { }

    void onShow() { onState(); }

    void onHide() { }

    /** The state from the work profile changed (or linking failed). */
    void onState() { }

    // ---- Building blocks of the Windows look ----

    LinearLayout group() {
        LinearLayout group = new LinearLayout(a);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_group);
        group.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = a.dp(22);
        content.addView(group, params);
        return group;
    }

    RowView row(LinearLayout group, int icon, int tileColor, String title, String caption) {
        if (group.getChildCount() > 0) separator(group);
        RowView row = new RowView(a);
        if (icon != 0) row.setIcon(icon, a.color(pastel(tileColor)), a.color(R.color.ink));
        row.setTitle(title).setCaption(caption);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    /** Row tiles use the soft card palette with dark glyphs, like the home screen. */
    static int pastel(int color) {
        if (color == R.color.green || color == R.color.teal) return R.color.pastel_green;
        if (color == R.color.orange) return R.color.pastel_orange;
        if (color == R.color.violet) return R.color.pastel_lavender;
        if (color == R.color.yellow) return R.color.pastel_yellow;
        return R.color.pastel_blue;
    }

    /** The accent «add» row: a round soft-blue badge with a plus. */
    RowView addRow(LinearLayout group, String title) {
        RowView row = row(group, 0, R.color.accent, title, null);
        row.setIcon(R.drawable.ic_add, a.color(R.color.blue_soft), a.color(R.color.accent));
        row.setTitleColor(a.color(R.color.accent));
        return row;
    }

    void separator(LinearLayout group) {
        View line = new View(a);
        line.setBackgroundColor(a.color(R.color.line));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, a.dp(1));
        params.setMarginStart(a.dp(56));
        group.addView(line, params);
    }

    TextView caption(LinearLayout parent, String text) {
        TextView caption = new TextView(a);
        caption.setTextAppearance(R.style.Caption);
        caption.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        caption.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(a.dp(12), -a.dp(12), a.dp(12), a.dp(18));
        parent.addView(caption, params);
        return caption;
    }

    TextView textAction(String text, View.OnClickListener click) {
        TextView action = new TextView(a);
        action.setTextAppearance(R.style.TextAction);
        action.setBackgroundResource(R.drawable.bg_text_action);
        action.setMinHeight(a.dp(40));
        action.setGravity(android.view.Gravity.CENTER_VERTICAL);
        action.setPadding(a.dp(8), 0, a.dp(8), 0);
        action.setText(text);
        action.setOnClickListener(click);
        return action;
    }

    TextView pill(LinearLayout parent, int style, int background, String text, View.OnClickListener click) {
        TextView pill = new TextView(new android.view.ContextThemeWrapper(a, style), null, 0);
        pill.setTextAppearance(style);
        pill.setBackgroundResource(background);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setMinHeight(a.dp(48));
        pill.setPadding(a.dp(16), 0, a.dp(16), 0);
        pill.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(a, R.animator.tap));
        pill.setText(text);
        pill.setOnClickListener(click);
        if (parent != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = a.dp(10);
            parent.addView(pill, params);
        }
        return pill;
    }
}
