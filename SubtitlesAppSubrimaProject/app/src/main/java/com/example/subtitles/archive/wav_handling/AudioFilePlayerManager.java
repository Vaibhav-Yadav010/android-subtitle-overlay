package com.example.subtitles.archive.wav_handling;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Manages playback of a single audio file (MP3/WAV) stored in your assets folder.
 */
public class AudioFilePlayerManager {
    private static final String TAG = "AudioFilePlayerMgr";

    private final Context context;
    private final String assetFilename;
    private MediaPlayer mediaPlayer;
    private File playbackFile;
    private boolean isPrepared = false;
    private boolean startRequested = false;
    private boolean destroyed = false;

    private Runnable onCompletionCallback;

    /**
     * @param context       Android context
     * @param assetFilename Filename in assets (e.g. "test.wav" or "track.mp3")
     * @throws IOException  if copying the asset fails
     */
    public AudioFilePlayerManager(Context context, String assetFilename) throws IOException {
        this.context = context.getApplicationContext();
        this.assetFilename = assetFilename;

        // Copy asset to a file we can play from
        playbackFile = com.example.subtitles.util.AssetUtils.copyFileIfNotExists(this.context, assetFilename);
        Log.i(TAG, "Asset copied to: " + playbackFile.getAbsolutePath());

        initPlayer();
    }

    private void initPlayer() {
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(playbackFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                if (destroyed || mediaPlayer != mp) return;
                isPrepared = true;
                Log.i(TAG, "MediaPlayer prepared, duration=" + mp.getDuration() + "ms");
                if (startRequested) {
                    startRequested = false;
                    mp.start();
                    Log.i(TAG, "Playback started after prepare");
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "Playback completed");
                if (onCompletionCallback != null) {
                    onCompletionCallback.run();
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Playback error what=" + what + " extra=" + extra);
                isPrepared = false;
                startRequested = false;
                return true; // handled
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Failed to set data source or prepare MediaPlayer", e);
        }
    }

    public boolean isPlaying() {
        return !destroyed && mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public void setOnCompletionCallback(Runnable callback) {
        this.onCompletionCallback = callback;
    }

    /** Starts playback if prepared; otherwise starts automatically when preparation completes. */
    public void start() {
        if (destroyed || mediaPlayer == null) {
            Log.w(TAG, "start() called after destroy or with null mediaPlayer");
            return;
        }
        if (!isPrepared) {
            startRequested = true;
            Log.w(TAG, "start() called before MediaPlayer prepared; queued until prepare completes");
            return;
        }
        if (mediaPlayer.isPlaying()) return;
        startRequested = false;
        mediaPlayer.start();
        Log.i(TAG, "Playback started");
    }

    /** Stops playback if playing, resets to start. */
    public void stop() {
        if (destroyed || mediaPlayer == null) {
            Log.w(TAG, "stop() called after destroy or with null mediaPlayer");
            return;
        }
        startRequested = false;
        if (!isPrepared) {
            Log.i(TAG, "stop() called while MediaPlayer is still preparing");
            return;
        }
        if (!mediaPlayer.isPlaying()) {
            Log.i(TAG, "stop() called but nothing was playing");
            return;
        }

        mediaPlayer.stop();
        Log.i(TAG, "Playback stopped");
        // after stop(), need to prepare again for future start()
        try {
            isPrepared = false;
            mediaPlayer.reset();
            mediaPlayer.setDataSource(playbackFile.getAbsolutePath());
            mediaPlayer.prepare();
            isPrepared = true;
            Log.i(TAG, "MediaPlayer re-prepared after stop");
        } catch (IOException | IllegalStateException e) {
            isPrepared = false;
            Log.e(TAG, "Failed to re-prepare after stop", e);
        }
    }

    /**
     * Releases all resources. After this, the manager should not be used.
     */
    public void destroy() {
        destroyed = true;
        startRequested = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
            isPrepared = false;
            Log.i(TAG, "MediaPlayer destroyed");
        }
    }
}
