package com.example.subtitles.model.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamAudioCapturer {
    public static final int chunkSizeMs = 250;
    private static final String TAG = "StreamAudioCapturer";
    private static volatile StreamAudioCapturer instance;
    private final Context context;
    private final MediaProjectionManager projectionManager;
    private final int sampleRate;
    private final int chunkSize;
    private final AtomicBoolean stopedMidCapturing = new AtomicBoolean(false);
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AudioManager audioManager;
    private final AudioManager.AudioPlaybackCallback playbackCallback;
    private MediaProjection projection;
    private AudioRecord recorder;
    private volatile OnAudioCaptureListener listener;
    private Thread captureThread;

    private StreamAudioCapturer(Context context, int sampleRate) {
        this.context = context.getApplicationContext();
        this.projectionManager = context.getSystemService(MediaProjectionManager.class);
        this.sampleRate = sampleRate;
        this.chunkSize = (chunkSizeMs * sampleRate) / 1000;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        playbackCallback = new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                super.onPlaybackConfigChanged(configs);
                boolean blockedDetected = false;
                for (AudioPlaybackConfiguration cfg : configs) {
                    int usage = cfg.getAudioAttributes().getUsage();
                    int policy = cfg.getAudioAttributes().getAllowedCapturePolicy();
                    if ((usage == AudioAttributes.USAGE_MEDIA ||
                            usage == AudioAttributes.USAGE_GAME ||
                            usage == AudioAttributes.USAGE_ASSISTANT)
                            && policy == AudioAttributes.ALLOW_CAPTURE_BY_NONE) {
                        blockedDetected = true;
                        break;
                    }
                }
                if (blockedDetected) {
                    Log.w(TAG, "An application has been detected that plays audio but blocks capture.");
                    if (listener != null) listener.onCaptureBlockedDetected();
                }
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static StreamAudioCapturer getInstance(Context context, int sampleRate) {
        if (instance == null) {
            synchronized (StreamAudioCapturer.class) {
                if (instance == null) instance = new StreamAudioCapturer(context, sampleRate);
            }
        }
        return instance;
    }

    public static synchronized void destroyInstance() {
        if (instance != null) {
            instance.destroy();
            instance = null;
        }
    }

    public void setOnAudioCaptureListener(OnAudioCaptureListener l) {
        this.listener = l;
    }

    public boolean onProjectionGranted(int resultCode, Intent data) {
        synchronized (lock) {
            if (projection != null) return true;
            projection = projectionManager.getMediaProjection(resultCode, data);
            if (projection == null) {
                Log.e(TAG, "Failed to obtain MediaProjection (null)");
                return false;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.e(TAG, "MediaProjection.onStop() called — projection permission revoked by system");
                    Log.e(TAG, "isCapturing=" + capturing.get());
                    Log.e(TAG, "stopedMidCapturing=" + stopedMidCapturing.get());
                    stop(true);
                    synchronized (lock) {
                        if (projection != null) projection = null;
                    }
                }
            }, main);
            if (stopedMidCapturing.get()) {
                if (!start()) Log.e(TAG, "Failed to start again capturing");
            }
            return true;
        }
    }

    public void onProjectionRevoked() {
        stop(true);
        synchronized (lock) {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public boolean start() {
        if (capturing.get()) return false;
        synchronized (lock) {
            if (capturing.get()) return false;
            if (projection == null) {
                Log.e(TAG, "Cannot start audio capture without an active MediaProjection");
                return false;
            }
            AudioRecord newRecorder = null;
            boolean callbackRegistered = false;
            try {
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
                int minBufBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minBufBytes <= 0) {
                    Log.e(TAG, "Invalid minimum AudioRecord buffer size: " + minBufBytes);
                    return false;
                }
                int bufSizeBytes = Math.max(minBufBytes, chunkSize * 2);
                AudioPlaybackCaptureConfiguration.Builder configBuilder =
                        new AudioPlaybackCaptureConfiguration.Builder(projection);
                int[] usages = new int[]{AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
                        AudioAttributes.USAGE_UNKNOWN, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioAttributes.USAGE_ASSISTANT};
                for (int usage : usages) {
                    try { configBuilder.addMatchingUsage(usage); }
                    catch (IllegalArgumentException e) { Log.w(TAG, "Invalid usage: " + usage); }
                }
                newRecorder = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSizeBytes)
                        .setAudioPlaybackCaptureConfig(configBuilder.build())
                        .build();
                if (newRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Recorder not initialized");
                    newRecorder.release();
                    return false;
                }
                newRecorder.startRecording();
                recorder = newRecorder;
                capturing.set(true);
                audioManager.registerAudioPlaybackCallback(playbackCallback, main);
                callbackRegistered = true;
                Thread newThread = new Thread(this::captureLoop, "AudioCaptureThread");
                captureThread = newThread;
                newThread.start();
                Log.i(TAG, "Audio capture started");
                return true;
            } catch (SecurityException e) {
                Log.e(TAG, "Permission error when starting capture", e);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start audio capture", e);
            }
            capturing.set(false);
            if (callbackRegistered) audioManager.unregisterAudioPlaybackCallback(playbackCallback);
            if (newRecorder != null) {
                try { newRecorder.stop(); } catch (IllegalStateException ignored) { }
                newRecorder.release();
                if (recorder == newRecorder) recorder = null;
            }
            captureThread = null;
            return false;
        }
    }

    private void captureLoop() {
        Log.d(TAG, "Entering capture loop with capturing=" + capturing.get());
        short[] buffer = new short[chunkSize];
        while (capturing.get() && !Thread.currentThread().isInterrupted()) {
            AudioRecord activeRecorder = recorder;
            if (activeRecorder == null) break;
            int read;
            try {
                read = activeRecorder.read(buffer, 0, buffer.length);
            } catch (RuntimeException e) {
                Log.e(TAG, "AudioRecord.read() failed", e);
                break;
            }
            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE || read < 0) {
                Log.e(TAG, "AudioRecord read error: " + read);
                break;
            }
            if (read > 0 && listener != null) listener.onAudioChunk(buffer, read);
        }
        if (capturing.compareAndSet(true, false)) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
            synchronized (lock) {
                if (captureThread == Thread.currentThread()) captureThread = null;
                if (recorder != null) {
                    try { recorder.stop(); } catch (IllegalStateException ignored) { }
                    recorder.release();
                    recorder = null;
                }
            }
            Log.w(TAG, "Audio capture loop exited unexpectedly");
        }
    }

    public void stop(boolean midCaptureing) {
        stopInternal(midCaptureing);
    }

    private void stopInternal(boolean midCaptureing) {
        stopedMidCapturing.set(midCaptureing);
        if (!capturing.getAndSet(false)) return;
        audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        Thread threadToJoin;
        AudioRecord recorderToStop;
        synchronized (lock) {
            threadToJoin = captureThread;
            recorderToStop = recorder;
            if (recorderToStop != null) {
                try { recorderToStop.stop(); } catch (IllegalStateException ignored) { }
            }
            if (threadToJoin != null) threadToJoin.interrupt();
        }
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try { threadToJoin.join(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for audio capture thread to stop");
            }
        }
        synchronized (lock) {
            if (captureThread == threadToJoin) captureThread = null;
            if (recorder == recorderToStop && recorderToStop != null) {
                recorderToStop.release();
                recorder = null;
            }
        }
        Log.i(TAG, "Audio capture stopped");
    }

    public boolean hasCapturableAudio() {
        List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
        for (AudioPlaybackConfiguration cfg : configs) {
            int usage = cfg.getAudioAttributes().getUsage();
            int policy = cfg.getAudioAttributes().getAllowedCapturePolicy();
            if ((usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME ||
                    usage == AudioAttributes.USAGE_UNKNOWN) && policy != AudioAttributes.ALLOW_CAPTURE_BY_NONE) {
                return true;
            }
        }
        return false;
    }

    private void destroy() {
        stop(false);
        synchronized (lock) {
            if (projection != null) {
                projection.stop();
                projection = null;
            }
        }
        Log.i(TAG, "AudioWindowCapturer destroyed");
    }

    public interface OnAudioCaptureListener {
        void onAudioChunk(short[] pcm, int length);
        default void onCaptureBlockedDetected() { }
    }
}
