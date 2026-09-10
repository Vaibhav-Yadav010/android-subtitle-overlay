package com.example.subtitles.view;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;

import androidx.appcompat.widget.SwitchCompat;

/** Persists the source-language auto-detection choice used by transcriptManager. */
public class CaptionXAutoDetectSwitch extends SwitchCompat {
    private static final String PREFS = "subrima_prefs";
    private boolean binding;

    public CaptionXAutoDetectSwitch(Context context) {
        super(context);
        init();
    }

    public CaptionXAutoDetectSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptionXAutoDetectSwitch(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        setChecked("auto".equals(prefs.getString("pref_source_lang", "auto")));
        setOnCheckedChangeListener((buttonView, checked) -> {
            if (binding) return;
            SharedPreferences p = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (checked) {
                p.edit().putString("pref_source_lang", "auto").apply();
            } else {
                String current = p.getString("pref_source_lang", "auto");
                if ("auto".equals(current)) {
                    p.edit().putString("pref_source_lang", "en").apply();
                    android.view.View source = getRootView().findViewById(com.example.subtitles.R.id.sourceLanguageButton);
                    if (source instanceof CaptionXActionTextView) {
                        ((CaptionXActionTextView) source).setText("English");
                    }
                }
            }
        });
    }

    public void syncFromPreference() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        binding = true;
        setChecked("auto".equals(prefs.getString("pref_source_lang", "auto")));
        binding = false;
    }
}
