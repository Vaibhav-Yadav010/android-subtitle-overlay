package com.example.subtitles.model.audio;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.content.pm.ServiceInfo;
import android.util.Log;

import com.example.subtitles.R;
import com.example.subtitles.view_model.transcriptManager;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Foreground service responsible for capturing audio from the device's screen/audio output.
 * <p>
 * Uses MediaProjection API to obtain a live capture of the audio stream.
 * Starts as a foreground service to ensure reliability during recording.
 * Broadcasts a "service ready" event only after projection initialization succeeds.
 */
public class AudioCaptureService extends Service {
    // Channel ID for the foreground notification
    private static final String CHANNEL_ID = "audio_capture_channel";
    // Capturer object that owns the MediaProjection and handles the audio streaming
    private StreamAudioCapturer capturer;
    /**
     * Called when the service is first created.
     * Initializes the audio capturer and sets up a foreground notification.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("AudioCaptureService", "onCreate()");
        // Create the notification channel (required for Android O and above)
        createNotificationChannel();
        // Initialize the singleton audio capturer with the sample rate from transcriptManager
        capturer = StreamAudioCapturer.getInstance(this, transcriptManager.sampleRate);
        // Build the foreground notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Capture Running")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        // Start the service in the foreground to avoid being killed by the system
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, notification);
        }

    }

    /**
     * Handles service start commands. Called whenever the service is started via startService().
     * Grants audio projection if valid data is provided and notifies other components.
     *
     * @param intent  the intent used to start the service
     * @param flags   flags indicating how to handle the service restart
     * @param startId unique integer representing this start request
     * @return int describing how the system should handle the service if killed
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("AudioCaptureService", "onStartCommand(), startId=" + startId + ", flags=" + flags + ", intent=" + intent);
        if (intent == null) {
            Log.e("AudioCaptureService", "Received null intent in onStartCommand");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // Retrieve projection data and result code from the intent
        int resultCode = intent.getIntExtra("PROJECTION_CODE", Activity.RESULT_CANCELED);
        Intent projectionData = intent.getParcelableExtra("PROJECTION_DATA");
        Log.d("AudioCaptureService", "Projection extras: resultCode=" + resultCode + ", projectionData=" + projectionData);
        // Validate the projection data
        if (resultCode == Activity.RESULT_OK && projectionData != null) {
            try {
                // The capturer is the single owner of the MediaProjection instance.
                boolean projectionGranted = capturer.onProjectionGranted(resultCode, projectionData);
                if (!projectionGranted) {
                    Log.e("AudioCaptureService", "Capturer failed to initialize MediaProjection; service is not ready");
                    stopSelf(startId);
                    return START_NOT_STICKY;
                }
                Log.d("AudioCaptureService", "Projection granted to capturer, sending ACTION_SERVICE_READY");
                // Broadcast that the service is ready only after successful projection initialization.
                LocalBroadcastManager.getInstance(this)
                        .sendBroadcast(new Intent("com.example.subtitles.ACTION_SERVICE_READY"));
                Log.d("AudioCaptureService", "Broadcast sent: ACTION_SERVICE_READY");
            } catch (SecurityException e) {
                // Catch any security exceptions in case permissions are missing
                Log.e("AudioCaptureService", "SecurityException while starting projection", e);
                stopSelf(startId);
            } catch (RuntimeException e) {
                // Catch runtime failures while initializing the projection through the capturer.
                Log.e("AudioCaptureService", "RuntimeException while initializing projection", e);
                stopSelf(startId);
            }
        } else {
            Log.e("AudioCaptureService", "Invalid projection data or resultCode");
            stopSelf(startId);
        }
        // Do not restart service automatically if killed
        return START_NOT_STICKY;
    }
    /**
     * Creates a notification channel for foreground service notifications.
     * Required for Android O (API 26) and above.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Capture Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Channel used for audio capture foreground service");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }


    /**
     * Called when the service is destroyed.
     * Stops the MediaProjection and releases resources from the capturer.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("AudioCaptureService", "onDestroy()");
        // The capturer owns the MediaProjection and is responsible for stopping it.
        if (capturer != null) {
            capturer.onProjectionRevoked();
            capturer = null;
        }
    }
    /**
     * Returns an IBinder for bound services. This service does not support binding.
     *
     * @param intent the intent used to bind
     * @return null because binding is not allowed
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
