// WhisperTranscriber.java
package com.example.subtitles.model.transcription.correction.whisper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import android.os.Build;

import com.example.subtitles.model.transcription.correction.transcriptSegment;
import com.example.subtitles.model.transcription.correction.whisper.lib.WhisperContext;
import com.example.subtitles.util.AssetUtils;
import com.example.subtitles.view_model.transcriptManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming, chunked transcription using Whisper.
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class WhisperTranscriber {
    private static final String TAG = "WhisperTranscriber";
    public static final String MODEL_PATH = AssetUtils.WHISPER_TINY_MODEL_FILE;
    private static WhisperTranscriber instance;

    private static final int SAMPLE_RATE = transcriptManager.sampleRate;
    public static final int CHUNK_SEC = 30;
    public static final int OVERLAP_SEC = 2;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SEC;
    private final int chunkSamples = CHUNK_SEC * SAMPLE_RATE;
    private final int overlapSamples = OVERLAP_SEC * SAMPLE_RATE;
    private final float[] buffer = new float[CHUNK_SAMPLES];
    private int bufferLen = 0;
    private int consecutiveTranscriptionFailures = 0;
    private static final int MAX_TRANSCRIPTION_RETRIES = 2;

    // Bound queued audio so a stalled native transcription cannot grow memory without limit.
    private static final int MAX_QUEUED_CHUNKS = 8;
    private final BlockingQueue<float[]> audioQueue;
    private Thread processingThread;
    private volatile WhisperContext ctx = null;
    private volatile boolean isDone = false;
    private Whisperlistener listener;
    private final Handler mainHandler;
    private final AtomicBoolean running;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private String lang = "";
    private long counterProcessing;
    private long sumProcessingTime;

    private WhisperTranscriber(Context context) {
        if (OVERLAP_SEC >= CHUNK_SEC) {
            throw new IllegalArgumentException("OVERLAP_SEC must be less than CHUNK_SEC");
        }
        this.ctx = WhisperContext.getInstance(context, MODEL_PATH);
        this.audioQueue = new LinkedBlockingQueue<>(MAX_QUEUED_CHUNKS);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
    }

    public static synchronized WhisperTranscriber getInstance(Context appContext) throws IOException {
        if (instance == null) {
            instance = new WhisperTranscriber(appContext);
            Log.i(TAG, "WhisperTranscriber initialized");
        }
        return instance;
    }

    public void appendAudio(float[] samples) {
        if (samples == null || samples.length == 0 || !running.get() || closing.get()) return;
        float[] copy = samples.clone();
        if (!audioQueue.offer(copy)) {
            // Drop the oldest queued chunk to keep latency bounded rather than accumulating stale audio.
            audioQueue.poll();
            audioQueue.offer(copy);
        }
    }

    private void processingLoop() {
        try {
            while (!isDone) {
                float[] chunk = audioQueue.take();

                if (chunk != null && chunk.length > 0) {
                    int incoming = chunk.length;
                    if (incoming >= buffer.length) {
                        // Keep the newest samples when a caller supplies more than one full Whisper window.
                        System.arraycopy(chunk, incoming - buffer.length, buffer, 0, buffer.length);
                        bufferLen = buffer.length;
                    } else {
                        if (bufferLen + incoming > buffer.length) {
                            int overflow = (bufferLen + incoming) - buffer.length;
                            System.arraycopy(buffer, overflow, buffer, 0, bufferLen - overflow);
                            bufferLen -= overflow;
                        }
                        System.arraycopy(chunk, 0, buffer, bufferLen, incoming);
                        bufferLen += incoming;
                    }
                }

                if (bufferLen >= chunkSamples) {
                    Log.d(TAG, "🟦 bufferLen = " + bufferLen + " / " + chunkSamples);
                    if (doTranscribe(chunkSamples)) {
                        System.arraycopy(buffer, chunkSamples - overlapSamples, buffer, 0, overlapSamples);
                        bufferLen = overlapSamples;
                        consecutiveTranscriptionFailures = 0;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean doTranscribe(int len) {
        WhisperContext context = ctx;
        if (context == null) {
            notifyError(new IllegalStateException("WhisperContext is unavailable"));
            return false;
        }
        float[] toTranscribe = Arrays.copyOf(buffer, len);
        try {
            long start = System.currentTimeMillis();
            Log.i(TAG, " Starting transcription of " + len + " samples...");
            List<transcriptSegment> segs = context.transcribeWithTime(toTranscribe);
            long duration = System.currentTimeMillis() - start;
            Log.i(TAG, " Transcription completed in " + duration + "ms, " + segs.size() + " segments");
            if (!segs.isEmpty()) lang = context.detectLanguage();
            sumProcessingTime += duration;
            counterProcessing += 1;
            if (duration / 1000L > CHUNK_SEC) {
                resetAll();
            }
            if (!segs.isEmpty()) notifyListener(segs, duration / 1000L > CHUNK_SEC);
            return true;
        } catch (Exception e) {
            consecutiveTranscriptionFailures++;
            Log.e(TAG, " Error during transcription (attempt " + consecutiveTranscriptionFailures + "/"
                    + MAX_TRANSCRIPTION_RETRIES + ")", e);
            notifyError(e);
            if (consecutiveTranscriptionFailures >= MAX_TRANSCRIPTION_RETRIES) {
                Log.e(TAG, "Dropping failed Whisper window after bounded retries");
                bufferLen = 0;
                consecutiveTranscriptionFailures = 0;
            }
            return false;
        }
    }

    private void resetAll() {
        audioQueue.clear();
        bufferLen = 0;
        consecutiveTranscriptionFailures = 0;
        lang = "";
        counterProcessing = 0;
        sumProcessingTime = 0;
    }

    private static void joinUninterruptibly(Thread worker) {
        boolean interrupted = false;
        while (true) {
            try {
                worker.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void start() {
        if (closing.get()) {
            throw new IllegalStateException("WhisperTranscriber is closing");
        }
        if (running.get()) return;
        if (processingThread != null && processingThread.isAlive()) {
            throw new IllegalStateException("Cannot start Whisper while previous worker is still stopping");
        }
        if (ctx == null) {
            throw new IllegalStateException("WhisperContext is closed");
        }
        isDone = false;
        resetAll();
        running.set(true);
        this.processingThread = new Thread(this::processingLoop, "WhisperProcessor");
        this.processingThread.start();
    }

    public synchronized void stop(@Nullable Runnable onStopped) {
        if (!running.getAndSet(false)) {
            if (onStopped != null) onStopped.run();
            return;
        }
        isDone = true;
        Thread worker = processingThread;
        if (worker != null) {
            worker.interrupt();
        }
        new Thread(() -> {
            if (worker != null && Thread.currentThread() != worker) {
                joinUninterruptibly(worker);
            }
            synchronized (WhisperTranscriber.this) {
                if (processingThread == worker) {
                    processingThread = null;
                }
            }

            if (counterProcessing > 0) {
                long avg = sumProcessingTime / counterProcessing;
                Log.i(TAG, " WhisperTranscriber stopped — total=" + counterProcessing +
                        " transcriptions, avg=" + avg + "ms");
            } else {
                Log.i(TAG, " WhisperTranscriber stopped — no transcriptions performed.");
            }
            resetAll();

            if (onStopped != null) {
                mainHandler.post(onStopped);
            }
        }, "WhisperStopper").start();
    }

    public void close() {
        synchronized (WhisperTranscriber.class) {
            if (!closing.compareAndSet(false, true)) {
                return;
            }

            Thread worker;
            synchronized (this) {
                running.set(false);
                isDone = true;
                worker = processingThread;
                if (worker != null) {
                    worker.interrupt();
                }
            }

            if (worker != null && Thread.currentThread() != worker) {
                joinUninterruptibly(worker);
            }

            synchronized (this) {
                if (processingThread == worker) {
                    processingThread = null;
                }
                resetAll();
            }

            WhisperContext context = ctx;
            try {
                if (context != null) {
                    context.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to close WhisperContext", e);
            } finally {
                synchronized (this) {
                    ctx = null;
                }
                if (instance == this) {
                    instance = null;
                }
            }
        }
    }

    public void setListener(Whisperlistener listener) {
        this.listener = listener;
    }

    public String getCurrentLang() {
        return lang;
    }

    private void notifyListener(List<transcriptSegment> currentSegment, boolean proformReset) {
        if (listener != null) {
            mainHandler.post(() -> listener.onResult(currentSegment, proformReset));
        }
    }

    private void notifyError(Exception e) {
        if (listener != null) {
            mainHandler.post(() -> listener.onError(e));
        }
    }

    public interface Whisperlistener {
        void onResult(@NonNull List<transcriptSegment> text, boolean proformReset);
        void onError(@NonNull Exception e);
    }
}
