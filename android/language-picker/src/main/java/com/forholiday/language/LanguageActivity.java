package com.forholiday.language;

import android.Manifest;
import android.app.Activity;
import android.app.LocaleManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class LanguageActivity extends Activity {
    private static final int BACKGROUND = 0xfff6faf7;
    private static final int INK = 0xff182523;
    private static final int TEAL = 0xff08786d;
    private static final int WHITE = 0xffffffff;
    private static final int MUTED = 0xff52645e;
    private static final int BORDER = 0xffd7e4df;
    private static final int SELECTED = 0xffe4f3ed;
    private static final int HERO = 0xffe1f4e9;
    private static final int CURRENT = 0xfffff2d8;
    private static final String SELECTION_KEY = "selected_language_tag";

    private record Language(String tag, String label, String flag) {}
    private static final Language[] LANGUAGES = {
        new Language("ja-JP", "日本語", "🇯🇵"), new Language("en-US", "English", "🇺🇸"),
        new Language("ko-KR", "한국어", "🇰🇷"), new Language("zh-CN", "简体中文", "🇨🇳"),
        new Language("zh-TW", "繁體中文", "🇹🇼")
    };
    private Language selectedLanguage;
    private Button applyButton;

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        String tag = savedState == null ? systemLocales().get(0).toLanguageTag()
            : savedState.getString(SELECTION_KEY);
        for (Language language : LANGUAGES) {
            if (language.tag().equals(tag)) selectedLanguage = language;
        }
        showLanguages();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedLanguage != null) outState.putString(SELECTION_KEY, selectedLanguage.tag());
    }

    private void showLanguages() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BACKGROUND);
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(14));
        scroll.addView(content);
        setContentView(screen);
        screen.requestApplyInsets();
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightBars, lightBars);
        }

        Locale current = systemLocales().get(0);
        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(16), dp(14), dp(10), dp(14));
        hero.setBackground(shape(HERO, 22, 0));
        content.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        hero.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = addText(heading, getString(R.string.app_name), 29, INK, true);
        title.setAccessibilityHeading(true);
        addText(heading, getString(R.string.choose_language), 15, MUTED, false);
        ImageView globe = new ImageView(this);
        globe.setImageResource(R.drawable.language_mascot);
        globe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(globe, new LinearLayout.LayoutParams(dp(84), dp(84)));

        LinearLayout currentCard = new LinearLayout(this);
        currentCard.setOrientation(LinearLayout.VERTICAL);
        currentCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        currentCard.setBackground(shape(CURRENT, 18, 0));
        LinearLayout.LayoutParams currentLayout = new LinearLayout.LayoutParams(-1, -2);
        currentLayout.topMargin = dp(12);
        content.addView(currentCard, currentLayout);
        addText(currentCard, getString(R.string.current_language), 13, MUTED, true);
        addText(currentCard, current.getDisplayName(current), 19, INK, true);

        TextView choices = addText(content, getString(R.string.available_languages), 15, INK, true);
        choices.setPadding(0, dp(18), 0, dp(8));
        choices.setAccessibilityHeading(true);
        RadioGroup languages = new RadioGroup(this);
        languages.setOrientation(RadioGroup.VERTICAL);
        content.addView(languages);
        int checkedId = View.NO_ID;

        for (Language language : LANGUAGES) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(language);
            option.setText(getString(R.string.language_option, language.flag(), language.label()));
            option.setContentDescription(language.label());
            option.setTextSize(18);
            option.setTextColor(INK);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPaddingRelative(dp(16), dp(6), dp(14), dp(6));
            option.setCompoundDrawablePadding(dp(10));
            option.setMinHeight(dp(56));
            option.setButtonDrawable((android.graphics.drawable.Drawable) null);
            option.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.language_choice_indicator, 0);
            StateListDrawable card = new StateListDrawable();
            card.addState(new int[] {android.R.attr.state_checked}, shape(SELECTED, 17, TEAL));
            card.addState(new int[0], shape(WHITE, 17, BORDER));
            option.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2008786d), card, null));
            RadioGroup.LayoutParams row = new RadioGroup.LayoutParams(-1, -2);
            row.bottomMargin = dp(7);
            languages.addView(option, row);
            if (language.equals(selectedLanguage)) checkedId = option.getId();
        }
        if (checkedId != View.NO_ID) languages.check(checkedId);

        LinearLayout footer = new LinearLayout(this);
        footer.setPadding(dp(22), dp(12), dp(22), dp(14));
        footer.setBackgroundColor(WHITE);
        screen.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        applyButton = new Button(this);
        applyButton.setAllCaps(false);
        applyButton.setText(R.string.apply_language);
        applyButton.setTextColor(WHITE);
        applyButton.setTextSize(17);
        applyButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        applyButton.setMinHeight(dp(54));
        applyButton.setStateListAnimator(null);
        applyButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), shape(TEAL, 14, 0), null));
        footer.addView(applyButton, new LinearLayout.LayoutParams(-1, -2));
        applyButton.setOnClickListener(view -> {
            if (selectedLanguage != null) changeLanguage(selectedLanguage);
        });
        languages.setOnCheckedChangeListener((group, id) -> {
            View checked = group.findViewById(id);
            selectedLanguage = checked == null ? null : (Language) checked.getTag();
            updateApplyButton();
        });
        updateApplyButton();
    }

    private void updateApplyButton() {
        boolean canApply = selectedLanguage != null
            && !selectedLanguage.tag().equals(systemLocales().get(0).toLanguageTag());
        applyButton.setEnabled(canApply);
        applyButton.setAlpha(canApply ? 1f : .55f);
    }

    private LocaleList systemLocales() {
        if (Build.VERSION.SDK_INT >= 33) return getSystemService(LocaleManager.class).getSystemLocales();
        return getResources().getConfiguration().getLocales();
    }

    private void changeLanguage(Language language) {
        LocaleList current = systemLocales();
        Locale requested = Locale.forLanguageTag(language.tag());
        if (requested.equals(current.get(0))) return;
        if (checkSelfPermission(Manifest.permission.CHANGE_CONFIGURATION) != PackageManager.PERMISSION_GRANTED
                || !Settings.System.canWrite(this)) {
            Toast.makeText(this, R.string.setup_needed, Toast.LENGTH_LONG).show();
            return;
        }

        LinkedHashSet<Locale> order = new LinkedHashSet<>();
        order.add(requested);
        for (int i = 0; i < current.size(); i++) order.add(current.get(i));
        Configuration change = new Configuration();
        change.setLocales(new LocaleList(order.toArray(new Locale[0])));
        try {
            // Android has no public system locale setter. Call the Binder interface, not the hidden proxy class.
            Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Class.forName("android.app.IActivityManager")
                .getMethod("updatePersistentConfiguration", Configuration.class).invoke(manager, change);
            showLanguages();
        } catch (ReflectiveOperationException | SecurityException e) {
            Log.w("PhoneLanguage", "System locale update failed", e);
            Toast.makeText(this, R.string.change_failed, Toast.LENGTH_LONG).show();
        }
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private TextView addText(LinearLayout content, String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(label);
        return label;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
