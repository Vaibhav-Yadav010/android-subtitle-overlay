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

    /// Logging tag
    private static final String TAG = "MainPipeline";

    /// Core transcription engine
    private final transcriptManager transcriber;

    /// Translator instance
    private final MlKitTranslator translator;

    /// Accumulates current transcript
    private final StringBuilder transcript = new StringBuilder();

    /// Application context
    private final Context cxt;

    /// Tracks whether the pipeline is started
    private final AtomicBoolean started = new AtomicBoolean(false);

    /// Tracks whether destroy has been requested
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    /// Main thread handler for UI updates and scheduling
    private final Handler handler = new Handler(Looper.getMainLooper());

    /// Pipeline listener for callbacks
    private volatile Listener listener;

    /// Map of supported languages for Google Translate / translation validation
    private JSONObject googleLangMap;

    /// Current source language settings
    private String sourceLang = "auto"; // user-selected source language
    private String srcLang = "en";      // active detected/used source language
    private String subtitleLang = "en"; // active subtitle/translation target language

    /// Indicates whether subtitle overlay service is initialized and ready
    private boolean subtitlesServiceReady = false;

    /**
     * Runnable for resetting subtitles after a timeout.
     * Clears overlay and notifies listener with empty strings.
     */
    private final Runnable resetRunnable = () -> {
        Log.d(TAG, "Resetting subtitles due to timeout");
        // Notify listener with empty strings to reset UI and translation
        if (listener != null) {
            listener.onTranscriptionUpdate("");
            listener.onTransltionUpdate("");
        }
        // Clear the overlay as well
        if(subtitlesServiceReady) {
            SubtitleOverlayService.updateText("");
        }
    };

    /**
     * Constructs the MainPipeline instance.
     * Initializes translator, loads language map, and sets up the transcriptManager listener.
     *
     * @param context Application context
     * @throws IOException if loading google_dict.json fails
     */
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
                    if(subtitlesServiceReady) {
                        SubtitleOverlayService.updateText(fullText);
                    }
                } else if (translator != null && !srcLang.equals(subtitleLang)) {
                    if (!translator.isReady()) {
                        if (listener != null) {
                            listener.onTransltionUpdate("Loading translation…");
                        }
                    } else {
                        try {
                            translator.translate(lastSourceSentence, fullText, new MlKitTranslator.TranslationCallback() {
                                @Override
                                public void onResult(String fullTranslated, String translated) {
                                    String displayText = trimToLastNUnits(translated, subtitleLang, MAX_SUBTITLES_WORDS);
                                    if (!translated.isEmpty() && subtitlesServiceReady) {
                                        SubtitleOverlayService.updateText(displayText);
                                    }
                                    if (listener != null) {
                                        listener.onTransltionUpdate(fullTranslated);
                                    }
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
                    if (listener != null) {
                        listener.onTransltionUpdate("No translation needed");
                    }
                }
                handler.postDelayed(resetRunnable, 2000);
            }

            @Override
            public void onError(String e) {
                notifyError(e);
            }

            @Override
            public void onModelTranscriptChange(String newLang) {
                if (!newLang.isEmpty() && (!newLang.equals(srcLang))) {
                    srcLang = newLang;
                    setLanguage(subtitleLang);
                }
            }

            @Override
            public void onLanguageDetected(String lang) {
                if (listener != null) {
                    listener.onLanguageDetected("Language: " + lang + " : " + lang + "(X)");
                }
            }

            @Override
            public void onLanguageChange(String lang) {
                if (listener != null) {
                    listener.onLanguageDetected("Language: " + lang + " : " + lang + "(V)");
                }
            }
        });
    }

    private String trimToLastNUnits(String text, String langTag, int maxUnits) {
        if (text == null || text.isEmpty() || maxUnits <= 0) {
            return "";
        }
        Locale locale = Locale.forLanguageTag(langTag);
        BreakIterator bi = BreakIterator.getWordInstance(locale);
        bi.setText(text);
        List<Integer> boundaries = new ArrayList<>();
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String piece = text.substring(start, end);
            if (!piece.isEmpty() && Character.isLetterOrDigit(piece.codePointAt(0))) {
                boundaries.add(start);
            }
        }
        boundaries.add(text.length());
        int total = boundaries.size() - 1;
        if (total <= maxUnits) {
            return text;
        }
        int cutIndex = boundaries.get(total - maxUnits);
        return text.substring(cutIndex).trim();
    }

    /**
     * Updates pipeline parameters from SharedPreferences.
     * An explicit source-language change is committed to the pipeline only after
     * transcriptManager/Vosk reports that the requested model is active.
     */
    public void setParmeters() {
        if (!started.get()) {
            Log.i(TAG, "pipeline not working (running) yet there is no need to do it now...");
            return;
        }
        SharedPreferences prefs = cxt.getSharedPreferences("subrima_prefs", Context.MODE_PRIVATE);
        String selectedSourceLang = prefs.getString("pref_source_lang", "auto");
        sourceLang = selectedSourceLang;

        String selectedSubtitleLang = prefs.getString("pref_subtitle_lang", "en");
        boolean sourceChanged = !selectedSourceLang.equals("auto") && !selectedSourceLang.equals(srcLang);
        boolean targetChanged = !subtitleLang.equals(selectedSubtitleLang);

        // transcriptManager owns the Vosk model switch. Do not change srcLang here;
        // onModelTranscriptChange() commits it after the model actually changes.
        if (targetChanged || (sourceChanged && selectedSourceLang.equals(srcLang))) {
            if (!setLanguage(selectedSubtitleLang)) {
                notifyError("problem changing translation languages...");
            }
        }
        transcriber.setParmeters();

        // If only the target changed, source and target can be applied immediately.
        if (!sourceChanged && targetChanged) {
            // setLanguage() above already committed subtitleLang on success.
        }
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
                    @Override
                    public void onReady() {
                        Log.i(TAG, "Translator models are ready after resume");
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "Translator failed to resume", e);
                        notifyError(e.getMessage());
                    }
                });
            }
            Intent overlayIntent = new Intent(cxt, SubtitleOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cxt.startForegroundService(overlayIntent);
            } else {
                cxt.startService(overlayIntent);
            }
            SubtitleOverlayService.showOverlay();
            subtitlesServiceReady = true;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Pipeline start failed", e);
            try {
                transcriber.stop();
            } catch (Exception cleanupError) {
                Log.w(TAG, "Failed to clean up transcriber after start failure", cleanupError);
            }
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
        if (translator != null) {
            translator.pause();
        }
        if(srcLang.isEmpty()) {
            srcLang = "en";
        }
        started.set(false);
    }

    public synchronized void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }
        stop();
        cxt.stopService(new Intent(cxt, SubtitleOverlayService.class));
        transcriber.close();
        if (translator != null) {
            try {
                translator.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing Translator", e);
            }
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
            } else {
                Log.w(TAG, "Translator instance is null – cannot switch language");
                return false;
            }
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
