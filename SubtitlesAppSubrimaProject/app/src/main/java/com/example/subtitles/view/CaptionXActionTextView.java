package com.example.subtitles.view;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.widget.AppCompatTextView;

import com.example.subtitles.R;

import java.util.ArrayList;
import java.util.List;

/** Interactive home-screen control for CaptionX. */
public class CaptionXActionTextView extends AppCompatTextView {
    private static final String PREFS = "subrima_prefs";

    public CaptionXActionTextView(Context context) {
        super(context);
        init();
    }

    public CaptionXActionTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptionXActionTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> performAction(String.valueOf(getTag())));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshLanguageLabel();
    }

    private void refreshLanguageLabel() {
        String action = String.valueOf(getTag());
        if (!"source".equals(action) && !"target".equals(action)) return;
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String code = prefs.getString("source".equals(action) ? "pref_source_lang" : "pref_subtitle_lang",
                "source".equals(action) ? "auto" : "en");
        setText(languageName(code));
    }

    private void performAction(String action) {
        switch (action) {
            case "source":
                chooseLanguage(true);
                break;
            case "target":
                chooseLanguage(false);
                break;
            case "swap":
                swapLanguages();
                break;
            case "audio_microphone":
                Toast.makeText(getContext(),
                        "Microphone capture is not supported by the current playback-capture engine.",
                        Toast.LENGTH_SHORT).show();
                break;
            case "audio_system":
                View startButton = getRootView().findViewById(R.id.toggleMainButton);
                if (startButton != null) startButton.performClick();
                break;
            case "engine_local":
                Toast.makeText(getContext(), "Local Vosk is active.", Toast.LENGTH_SHORT).show();
                break;
            case "engine_remote":
                Toast.makeText(getContext(),
                        "Remote Whisper is not enabled in this build.", Toast.LENGTH_SHORT).show();
                break;
            case "permission_microphone":
                requestMicrophonePermission();
                break;
            case "permission_overlay":
                requestOverlayPermission();
                break;
            default:
                break;
        }
    }

    private void requestMicrophonePermission() {
        if (!(getContext() instanceof Activity)) return;
        Activity activity = (Activity) getContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
        } else {
            Toast.makeText(getContext(), "Microphone permission is already granted.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestOverlayPermission() {
        if (!(getContext() instanceof Activity)) return;
        Activity activity = (Activity) getContext();
        if (Settings.canDrawOverlays(activity)) {
            Toast.makeText(getContext(), "Overlay permission is already granted.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    private void chooseLanguage(boolean source) {
        String[] codes = getResources().getStringArray(R.array.lang_codes);
        String[] names = getResources().getStringArray(R.array.lang_names);
        List<String> filteredCodes = new ArrayList<>();
        List<String> filteredNames = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            if (!source && "auto".equals(codes[i])) continue;
            filteredCodes.add(codes[i]);
            filteredNames.add(names[i]);
        }

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = source ? "pref_source_lang" : "pref_subtitle_lang";
        String current = prefs.getString(key, source ? "auto" : "en");
        int checked = filteredCodes.indexOf(current);
        if (checked < 0) checked = 0;

        new AlertDialog.Builder(getContext())
                .setTitle(source ? "Source language" : "Translation language")
                .setSingleChoiceItems(filteredNames.toArray(new String[0]), checked, (dialog, which) -> {
                    String selectedCode = filteredCodes.get(which);
                    prefs.edit().putString(key, selectedCode).apply();
                    setText(filteredNames.get(which));
                    if (source) {
                        View auto = getRootView().findViewById(R.id.autoDetectSwitch);
                        if (auto instanceof CaptionXAutoDetectSwitch) {
                            ((CaptionXAutoDetectSwitch) auto).syncFromPreference();
                        }
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void swapLanguages() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String source = prefs.getString("pref_source_lang", "auto");
        String target = prefs.getString("pref_subtitle_lang", "en");
        if ("auto".equals(source)) {
            Toast.makeText(getContext(), "Choose a source language before swapping.", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit()
                .putString("pref_source_lang", target)
                .putString("pref_subtitle_lang", source)
                .apply();
        View root = getRootView();
        CaptionXActionTextView sourceView = root.findViewById(R.id.sourceLanguageButton);
        CaptionXActionTextView targetView = root.findViewById(R.id.targetLanguageButton);
        if (sourceView != null) sourceView.setText(languageName(target));
        if (targetView != null) targetView.setText(languageName(source));
        View auto = root.findViewById(R.id.autoDetectSwitch);
        if (auto instanceof CaptionXAutoDetectSwitch) {
            ((CaptionXAutoDetectSwitch) auto).syncFromPreference();
        }
    }

    private String languageName(String code) {
        String[] codes = getResources().getStringArray(R.array.lang_codes);
        String[] names = getResources().getStringArray(R.array.lang_names);
        for (int i = 0; i < codes.length; i++) {
            if (code.equals(codes[i])) return names[i];
        }
        return code;
    }
}
