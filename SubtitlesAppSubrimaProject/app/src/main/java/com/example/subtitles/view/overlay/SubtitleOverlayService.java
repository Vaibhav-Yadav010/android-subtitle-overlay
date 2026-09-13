package com.example.subtitles.view.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.subtitles.R;

/**
 * Service responsible for displaying live subtitles as a floating overlay.
 */
public class SubtitleOverlayService extends Service {
    public static final int MINTIMESTAY = 700;
    private static final String TAG = "SubtitleOverlayService";

    private static final char RLE = '\u202B';
    private static final char LRE = '\u202A';
    private static final char PDF = '\u202C';
    private static final int NOTIFICATION_ID = 2002;
    private static final String CHANNEL_ID = "subtitle_overlay_channel";

    private static SubtitleOverlayService instance;
    private static String pendingTextBeforeCreate;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlayView;
    private TextView overlayText;
    private WindowManager.LayoutParams params;

    private boolean waitingTochange = false;
    private long lastUpdateTime = 0L;
    private String pendingText;
    private final Runnable applyRunnable = this::applyPendingSubtitle;
    private boolean overlayVisible = false;
    private int screenHeight;
    private int marginPx;
    private int initialOffsetPx;
    private int screenWidth;

    private static boolean isRTL(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
                return block == Character.UnicodeBlock.HEBREW
                        || block == Character.UnicodeBlock.ARABIC;
            }
        }
        return false;
    }

    /**
     * Queues an update even if the service has not finished creating its view yet.
     * This removes the startup race where MainPipeline can emit its first subtitle
     * before Android has delivered SubtitleOverlayService.onCreate().
     */
    public static void updateText(String text) {
        SubtitleOverlayService current = instance;
        if (current != null) {
            current.handler.post(() -> current.handleTextUpdate(text));
            return;
        }
        synchronized (SubtitleOverlayService.class) {
            pendingTextBeforeCreate = text;
        }
    }

    public static void showOverlay() {
        SubtitleOverlayService current = instance;
        if (current != null) {
            current.resetState();
            current.runOnUi(() -> current.setOverlayVisible(true));
        }
    }

    public static void hideOverlay() {
        SubtitleOverlayService current = instance;
        if (current != null) {
            current.runOnUi(() -> current.setOverlayVisible(false));
        }
    }

    private void handleTextUpdate(String text) {
        if (!overlayVisible) return;
        long delay;
        synchronized (this) {
            pendingText = text;
            if (waitingTochange) return;
            waitingTochange = true;
            delay = Math.max(0, MINTIMESTAY + lastUpdateTime - System.currentTimeMillis());
        }
        handler.postDelayed(applyRunnable, delay);
    }

    private void applyPendingSubtitle() {
        String text;
        synchronized (this) {
            waitingTochange = false;
            if (pendingText == null) return;
            lastUpdateTime = System.currentTimeMillis();
            text = pendingText;
        }
        boolean rtl = isRTL(text);
        String wrapped = (rtl ? RLE : LRE) + text + PDF;
        try {
            overlayText.setText(wrapped);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Tried to update overlay after the view was detached", e);
        }
    }

    private void resetState() {
        synchronized (this) {
            pendingText = null;
            waitingTochange = false;
        }
    }

    private void runOnUi(Runnable r) {
        handler.post(r);
    }

    private void setOverlayVisible(boolean visible) {
        if (visible == overlayVisible) return;
        overlayVisible = visible;
        try {
            if (visible) {
                if (overlayView.getParent() == null) {
                    windowManager.addView(overlayView, params);
                }
            } else {
                if (overlayView.getParent() != null) {
                    windowManager.removeViewImmediate(overlayView);
                }
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Overlay view state changed unexpectedly", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        overlayText = overlayView.findViewById(R.id.overlay_text);
        // The overlay must never present a static placeholder as if it were a translation.
        overlayText.setText("");

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;

        marginPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, dm);
        int fixedW = screenW - 2 * marginPx;
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        int offsetDp = 200;
        initialOffsetPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, offsetDp, dm);
        params.y = initialOffsetPx;
        makeDraggableVertical(overlayView, params, dm);

        String queued;
        synchronized (SubtitleOverlayService.class) {
            queued = pendingTextBeforeCreate;
            pendingTextBeforeCreate = null;
        }
        if (queued != null) {
            pendingText = queued;
        }
    }

    private void makeDraggableVertical(View view, WindowManager.LayoutParams p, DisplayMetrics dm) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int newY = (int) (event.getRawY() - v.getHeight() / 2);
                p.y = Math.max(marginPx,
                        Math.min(newY, screenHeight - v.getHeight() - marginPx));
                windowManager.updateViewLayout(view, p);
            }
            return true;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        setOverlayVisible(true);
        String queued = pendingText;
        if (queued != null && !queued.isEmpty()) {
            pendingText = null;
            handleTextUpdate(queued);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (overlayVisible && overlayView.getParent() != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Tried to remove an already detached overlay", e);
            }
        }
        if (instance == this) instance = null;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            params.width = screenWidth / 2;
            overlayView.post(() -> {
                params.y = screenHeight - overlayView.getHeight() - marginPx;
                params.y = Math.max(marginPx,
                        Math.min(params.y, screenHeight - overlayView.getHeight() - marginPx));
                if (overlayView.getParent() != null) {
                    try { windowManager.updateViewLayout(overlayView, params); }
                    catch (IllegalArgumentException e) { Log.w(TAG, "Subtitle view not attached", e); }
                }
            });
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.y = initialOffsetPx;
            if (overlayView.getParent() != null) {
                try { windowManager.updateViewLayout(overlayView, params); }
                catch (IllegalArgumentException e) { Log.w(TAG, "Subtitle view not attached", e); }
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("subtitles")
                .setContentText("Overlay service running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Subtitle Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}