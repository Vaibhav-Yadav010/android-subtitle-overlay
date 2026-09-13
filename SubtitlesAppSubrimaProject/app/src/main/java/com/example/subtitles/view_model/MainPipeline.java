package com.example.subtitles.view_model;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.example.subtitles.model.translation.MlKitTranslator;
import com.example.subtitles.view.overlay.SubtitleOverlayService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainPipeline is the central orchestrator for the transcription and subtitle workflow.
 *
 * Responsibilities include:
 * 1. Starting and stopping audio capture via transcriptManager.
 * 2. Handling source language detection and switching.
 * 3. Translating transcriptions to a target language using MlKitTranslator.
 * 4. Updating subtitles in the overlay service.
 * 5. Managing UI callbacks through the Listener interface.
 *
 * It combines transcription, translation, and display in a single pipeline,
 * allowing optional translation and smart handling of long subtitles.
 */
public class MainPipeline {
    /// Maximum number of words shown in subtitles at a time
    public static final int MAX_SUBTITLES_WORDS = 10;
    private static final String TAG = "MainPipeline";
    private final transcriptManager transcriber;
    private final MlKitTranslator translator;
    private final StringBuilder transcript = new StringBuilder();
    private final Context cxt;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private JSONObject googleLangMap;
    private String sourceLang = "auto";
    private String srcLang = "en";
    private String subtitleLang = "en";
    private boolean subtitlesServiceReady = false;

    private final Runnable resetRunnable = () -> {
        Log.d(TAG, "Resetting subtitles due to timeout");
        if (listener != null) {
            listener.onTranscriptionUpdate("");
            listener.onTransltionUpdate("");
        }
        if (subtitlesServiceReady) {
            SubtitleOverlayService.updateText("");
        }
    };

    public MainPipeline(Context context) throws IOException {
        this.cxt = context.getApplicationContext();
        MlKitTranslator tempTransltor = null;
        try {
            tempTransltor = new MlKitTranslator(this.cxt, srcLang, subtitleLang);
        } catch (Exception e) {
            Log.e(TAG, "Translator initialization failed", e);
            notifyError(e.getMessage());
        }
        translator = tempTransltor;
        try (InputStream is = this.cxt.getAssets().open("google_dict.json")) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            String json = new String(buffer, StandardCharsets.UTF_8);
            googleLangMap = new JSONObject(json);
            Log.d(TAG, "Google language map loaded with " + googleLangMap.length() + " entries");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load google_dict.json", e);
            googleLangMap = new JSONObject();
        }
        this.transcriber = transcriptManager.getInstance(this.cxt, srcLang, googleLangMap);
        this.transcriber.setListener(new transcriptManager.Listener() {
            @Override
            public void onTranscriptionUpdate(String lastSourceSentence, String fullText) {
                handler.removeCallbacks(resetRunnable);
                transcript.setLength(0);
                transcript.append(fullText);
                if (listener != null) {
                    listener.onTranscriptionUpdate(lastSourceSentence + "\n|||||||\n" + fullText);
                }
                if (fullText.isEmpty()) {
                    if (subtitlesServiceReady) SubtitleOverlayService.updateText(fullText);
                } else if (translator != null && !srcLang.equals(subtitleLang)) {
                    if (!translator.isReady()) {
                        if (listener != null) listener.onTransltionUpdate("Loading translation…");
                    } else {
                        try {
                            translator.translate(lastSourceSentence, fullText, new MlKitTranslator.TranslationCallback() {
                                @Override
                                public void onResult(String fullTranslated, String translated) {
                                    String displayText = trimToLastNUnits(translated, subtitleLang, MAX_SUBTITLES_WORDS);
                                    if (!translated.isEmpty() && subtitlesServiceReady) {
                                        SubtitleOverlayService.updateText(displayText);
                                    }
                                    if (listener != null) listener.onTransltionUpdate(fullTranslated);
                                }
                                @Override
                                public void onError(MlKitTranslator.TranslationException e) {
                                    Log.e("Pipeline", "Translation error", e);
                                }
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "Translator translate failed: ", e);
                        }
                    }
                } else {
                    String displayText = trimToLastNUnits(fullText, subtitleLang, MAX_SUBTITLES_WORDS);
                    Log.d("Pipeline", "NO Translated - Just Transcript: " + displayText);
                    if (!displayText.isEmpty() && subtitlesServiceReady) {
                        SubtitleOverlayService.updateText(displayText);
                    }
                    if (listener != null) listener.onTransltionUpdate("No translation needed");
                }
                handler.postDelayed(resetRunnable, 2000);
            }

            @Override
            public void onError(Exception e) {
                notifyError(e == null ? "Unknown transcription error" : e.getMessage());
            }

            @Override
            public void onModelTranscriptChange(String newLang) {
                if (!newLang.isEmpty() && !newLang.equals(srcLang)) {
                    srcLang = newLang;
                    setLanguage(subtitleLang);
                }
            }

            @Override
            public void onLanguageDetected(String lang) {
                if (listener != null) listener.onLanguageDetected("Language: " + lang + " : " + lang + "(X)");
            }

            @Override
            public void onLanguageChange(String lang) {
                if (listener != null) listener.onLanguageDetected("Language: " + lang + " : " + lang + "(V)");
            }
        });
    }

    private String trimToLastNUnits(String text, String langTag, int maxUnits) {
        if (text == null || text.isEmpty() || maxUnits <= 0) return "";
        Locale locale = Locale.forLanguageTag(langTag);
        BreakIterator bi = BreakIterator.getWordInstance(locale);
        bi.setText(text);
        List<Integer> boundaries = new ArrayList<>();
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String piece = text.substring(start, end);
            if (!piece.isEmpty() && Character.isLetterOrDigit(piece.codePointAt(0))) boundaries.add(start);
        }
        boundaries.add(text.length());
        int total = boundaries.size() - 1;
        if (total <= maxUnits) return text;
        int cutIndex = boundaries.get(total - maxUnits);
        return text.substring(cutIndex).trim();
    }

    public void setParmeters() {
        if (!started.get()) {
            Log.i(TAG, "pipeline not working (running) yet there is no need to do it now...");
            return;
        }
        SharedPreferences prefs = cxt.getSharedPreferences("subrima_prefs", Context.MODE_PRIVATE);
        sourceLang = prefs.getString("pref_source_lang", "auto");
        String selectedSubtitleLang = prefs.getString("pref_subtitle_lang", "en");
        boolean sourceChanged = !sourceLang.equals("auto") && !sourceLang.equals(srcLang);
        boolean targetChanged = !subtitleLang.equals(selectedSubtitleLang);
        if (!sourceChanged && targetChanged) {
            if (!setLanguage(selectedSubtitleLang)) notifyError("problem changing translation languages...");
        }
        transcriber.setParmeters();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public synchronized boolean start() {
        if (destroyed.get()) {
            Log.w(TAG, "Cannot start destroyed pipeline");
            return false;
        }
        if (started.get()) {
            Log.i(TAG, "Already translating");
            return false;
        }
        started.set(true);
        try {
            setParmeters();
            boolean transcriberStarted = transcriber.start();
            if (!transcriberStarted) {
                started.set(false);
                Log.w(TAG, "Audio capture failed to start; pipeline state reset");
                return false;
            }
            if (translator != null) {
                translator.resume(new MlKitTranslator.ReadyListener() {
                    @Override public void onReady() { Log.i(TAG, "Translator models are ready after resume"); }
                    @Override public void onError(Exception e) { Log.e(TAG, "Translator failed to resume", e); notifyError(e.getMessage()); }
                });
            }
            Intent overlayIntent = new Intent(cxt, SubtitleOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cxt.startForegroundService(overlayIntent);
            else cxt.startService(overlayIntent);
            SubtitleOverlayService.showOverlay();
            subtitlesServiceReady = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pipeline start failed", e);
            try { transcriber.stop(); } catch (Exception cleanupError) { Log.w(TAG, "Failed to clean up transcriber after start failure", cleanupError); }
            started.set(false);
            subtitlesServiceReady = false;
            handler.removeCallbacks(resetRunnable);
            return false;
        }
    }

    public synchronized void stop() {
        if (!started.get()) return;
        handler.removeCallbacks(resetRunnable);
        SubtitleOverlayService.hideOverlay();
        subtitlesServiceReady = false;
        transcriber.stop();
        if (translator != null) translator.pause();
        if (srcLang.isEmpty()) srcLang = "en";
        started.set(false);
    }

    public synchronized void destroy() {
        if (!destroyed.compareAndSet(false, true)) return;
        stop();
        cxt.stopService(new Intent(cxt, SubtitleOverlayService.class));
        transcriber.close();
        if (translator != null) {
            try { translator.close(); } catch (Exception e) { Log.w(TAG, "Error closing Translator", e); }
        }
        listener = null;
        Log.i(TAG, "Pipeline destroyed");
    }

    private void notifyError(String e) {
        Listener currentListener = listener;
        if (currentListener != null) currentListener.onError(e);
    }

    public boolean setLanguage(String newDstLang) {
        try {
            if (translator != null) {
                Log.d(TAG, "Requesting to switch translation language to: " + srcLang + "->" + newDstLang);
                boolean success = translator.setLanguages(srcLang, newDstLang);
                if (success) {
                    subtitleLang = newDstLang;
                    Log.i(TAG, "Successfully switched target language to: " + newDstLang);
                } else {
                    Log.w(TAG, "Failed to switch target language to: " + newDstLang);
                }
                return success;
            }
            Log.w(TAG, "Translator instance is null – cannot switch language");
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception while switching target language", e);
            return false;
        }
    }

    public interface Listener {
        void onLanguageDetected(String lang);
        void onTranscriptionUpdate(String fullText);
        void onTransltionUpdate(String translate);
        void onError(String e);
    }
}
