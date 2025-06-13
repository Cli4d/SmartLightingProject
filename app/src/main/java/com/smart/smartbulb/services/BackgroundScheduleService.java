package com.smart.smartbulb.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.smart.smartbulb.MainActivity;
import com.smart.smartbulb.R;
import com.smart.smartbulb.models.LightSettings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BackgroundScheduleService extends Service {
    private static final String TAG = "BackgroundScheduleService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "smart_bulb_schedule";

    // Background components
    private TuyaCloudApiService tuyaService;
    private LightSettings lightSettings;
    private SharedPreferences preferences;
    private Handler scheduleHandler;
    private Runnable scheduleChecker;
    private volatile boolean isServiceActive = false;

    // Schedule constants
    private static final int SUNSET_BRIGHTNESS = 80;
    private static final int BEDTIME_BRIGHTNESS = 20;
    private static final int MORNING_BRIGHTNESS = 80;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BackgroundScheduleService created");

        // Initialize components
        preferences = getSharedPreferences("smart_bulb_prefs", MODE_PRIVATE);
        scheduleHandler = new Handler(Looper.getMainLooper());

        // Create notification channel
        createNotificationChannel();

        // Initialize Tuya service for background operations
        initializeTuyaService();

        // Load settings from preferences
        loadSettingsFromPreferences();

        isServiceActive = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BackgroundScheduleService started");

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createServiceNotification());

        // Start schedule checking
        startScheduleChecker();

        // Return sticky so service restarts if killed
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new ScheduleServiceBinder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "BackgroundScheduleService destroyed");

        isServiceActive = false;

        // Stop schedule checking
        if (scheduleHandler != null && scheduleChecker != null) {
            scheduleHandler.removeCallbacks(scheduleChecker);
        }

        // Disconnect Tuya service
        if (tuyaService != null) {
            tuyaService.disconnect();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Smart Bulb Schedule",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background service for automatic light scheduling");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private android.app.Notification createServiceNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Smart Bulb Schedule Active")
                .setContentText("Monitoring for sunset and bedtime")
                .setSmallIcon(R.drawable.ic_smart_bulb)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void initializeTuyaService() {
        Log.d(TAG, "Initializing background Tuya service");

        tuyaService = new TuyaCloudApiService();

        // Get device ID from preferences
        String deviceId = preferences.getString("device_id", "bfc64cc8fa223bd6afxqtb");
        tuyaService.setDeviceId(deviceId);

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                Log.d(TAG, "Background Tuya connected: " + deviceName);
                sendScheduleNotification("Smart Bulb connected", "Auto-schedule is active");
            }

            @Override
            public void onDeviceDisconnected() {
                Log.w(TAG, "Background Tuya disconnected");
            }

            @Override
            public void onBrightnessChanged(int brightness) {
                Log.d(TAG, "Background brightness changed: " + brightness + "%");
                // Update preferences
                preferences.edit().putInt("current_brightness", brightness).apply();
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                Log.d(TAG, "Background light state changed: " + (isOn ? "ON" : "OFF"));
                // Update preferences
                preferences.edit().putBoolean("bulb_on", isOn).apply();
            }

            @Override
            public void onColorChanged(String hexColor) {
                Log.d(TAG, "Background color changed: " + hexColor);
                // Update preferences
                preferences.edit().putString("current_color", hexColor).apply();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Background Tuya error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Background Tuya success: " + message);
            }
        });

        // Connect to Tuya Cloud
        tuyaService.connect();
    }

    private void loadSettingsFromPreferences() {
        if (lightSettings == null) {
            lightSettings = new LightSettings();
        }

        // Load from SharedPreferences
        lightSettings.setAutoScheduleEnabled(preferences.getBoolean("auto_schedule_enabled", false));
        lightSettings.setSunsetTime(preferences.getString("sunset_time", "18:30"));
        lightSettings.setBedtime(preferences.getString("bedtime", "22:00"));
        lightSettings.setBulbOn(preferences.getBoolean("bulb_on", false));
        lightSettings.setBrightness(preferences.getInt("current_brightness", 100));
        lightSettings.setColor(preferences.getString("current_color", "#FFFFFF"));
        lightSettings.setUsername(preferences.getString("username", "User"));

        Log.d(TAG, "Settings loaded - Auto schedule: " + lightSettings.isAutoScheduleEnabled() +
                ", Sunset: " + lightSettings.getSunsetTime() +
                ", Bedtime: " + lightSettings.getBedtime());
    }

    private void startScheduleChecker() {
        scheduleChecker = new Runnable() {
            @Override
            public void run() {
                if (isServiceActive) {
                    // Reload settings from preferences in case they changed
                    loadSettingsFromPreferences();

                    // Check current time
                    checkScheduledTimes();

                    // Schedule next check in 30 seconds (more frequent than MainActivity)
                    scheduleHandler.postDelayed(this, 30 * 1000);
                }
            }
        };

        // Start immediately
        scheduleHandler.post(scheduleChecker);
    }

    private void checkScheduledTimes() {
        if (!lightSettings.isAutoScheduleEnabled()) {
            return;
        }

        // Only process if bulb is on
        if (!lightSettings.isBulbOn()) {
            Log.d(TAG, "Skipping schedule check - bulb is off");
            return;
        }

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String currentTime = sdf.format(new Date());

            Log.d(TAG, "Background checking time: " + currentTime +
                    " (sunset: " + lightSettings.getSunsetTime() +
                    ", bedtime: " + lightSettings.getBedtime() + ")");

            // Check for sunset time (dim to 80%)
            if (currentTime.equals(lightSettings.getSunsetTime()) &&
                    lightSettings.getBrightness() != SUNSET_BRIGHTNESS) {

                Log.d(TAG, "Background sunset trigger - dimming to 80%");
                executeScheduledBrightness(SUNSET_BRIGHTNESS, "sunset");
                sendScheduleNotification("🌅 Sunset Time", "Lights dimmed to 80% for evening comfort");
            }

            // Check for bedtime (dim to 20%)
            if (currentTime.equals(lightSettings.getBedtime()) &&
                    lightSettings.getBrightness() != BEDTIME_BRIGHTNESS) {

                Log.d(TAG, "Background bedtime trigger - dimming to 20%");
                executeScheduledBrightness(BEDTIME_BRIGHTNESS, "bedtime");
                sendScheduleNotification("🌙 Bedtime", "Lights dimmed to 20% for sleep");
            }

            // Check for morning (6:00 AM) - brighten to 80%
            if (currentTime.equals("06:00") &&
                    (lightSettings.getBrightness() < MORNING_BRIGHTNESS)) {

                Log.d(TAG, "Background morning trigger - brightening to 80%");
                executeScheduledBrightness(MORNING_BRIGHTNESS, "morning");
                sendScheduleNotification("☀️ Good Morning", "Lights brightened for the day");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in background schedule check", e);
        }
    }

    private void executeScheduledBrightness(int brightness, String reason) {
        if (tuyaService == null || !tuyaService.isConnected()) {
            Log.w(TAG, "Tuya service not connected, cannot execute scheduled brightness");
            return;
        }

        try {
            Log.d(TAG, "Executing scheduled brightness: " + brightness + "% - " + reason);

            // Send command to Tuya device
            tuyaService.setBrightness(brightness);

            // Update local settings
            lightSettings.setBrightness(brightness);

            // Save to preferences
            preferences.edit()
                    .putInt("current_brightness", brightness)
                    .putLong("last_scheduled_change", System.currentTimeMillis())
                    .putString("last_scheduled_reason", reason)
                    .apply();

            // Update dimmed state
            if (brightness == BEDTIME_BRIGHTNESS) {
                lightSettings.setDimmed(true);
                preferences.edit().putBoolean("is_dimmed", true).apply();
            } else {
                lightSettings.setDimmed(false);
                preferences.edit().putBoolean("is_dimmed", false).apply();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error executing scheduled brightness", e);
        }
    }

    private void sendScheduleNotification(String title, String message) {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_lightbulb)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build();

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify((int) System.currentTimeMillis(), notification);

        } catch (Exception e) {
            Log.e(TAG, "Error sending notification", e);
        }
    }

    // Public methods for external communication
    public void updateSettings(LightSettings settings) {
        if (settings != null) {
            this.lightSettings = settings;
            saveSettingsToPreferences();
            Log.d(TAG, "Settings updated in background service");
        }
    }

    private void saveSettingsToPreferences() {
        if (lightSettings != null) {
            preferences.edit()
                    .putBoolean("auto_schedule_enabled", lightSettings.isAutoScheduleEnabled())
                    .putString("sunset_time", lightSettings.getSunsetTime())
                    .putString("bedtime", lightSettings.getBedtime())
                    .putBoolean("bulb_on", lightSettings.isBulbOn())
                    .putInt("current_brightness", lightSettings.getBrightness())
                    .putString("current_color", lightSettings.getColor())
                    .putString("username", lightSettings.getUsername())
                    .putBoolean("is_dimmed", lightSettings.isDimmed())
                    .apply();
        }
    }

    public LightSettings getCurrentSettings() {
        loadSettingsFromPreferences();
        return lightSettings;
    }

    public boolean isTuyaConnected() {
        return tuyaService != null && tuyaService.isConnected();
    }

    public void reconnectTuya() {
        if (tuyaService != null) {
            tuyaService.connect();
        }
    }

    // Binder class for service communication
    public class ScheduleServiceBinder extends Binder {
        public BackgroundScheduleService getService() {
            return BackgroundScheduleService.this;
        }
    }
}