package com.smart.smartbulb.receivers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.smart.smartbulb.R;
import com.smart.smartbulb.services.TuyaCloudApiService;
import com.smart.smartbulb.services.ScheduleManager;
import com.smart.smartbulb.MainActivity;

/**
 * BroadcastReceiver that handles scheduled sunset and bedtime events
 */
public class ScheduleBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "ScheduleBroadcastReceiver";
    private static final String PREFS_NAME = "SmartBulbPrefs";
    private static final String PREF_DEVICE_ID = "device_id";

    // Notification channel and IDs
    private static final String NOTIFICATION_CHANNEL_ID = "smart_bulb_schedule";
    private static final String NOTIFICATION_CHANNEL_NAME = "Smart Bulb Schedule";
    private static final int NOTIFICATION_ID_SUNSET = 2001;
    private static final int NOTIFICATION_ID_BEDTIME = 2002;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action == null) {
            Log.w(TAG, "Received intent with null action");
            return;
        }

        Log.d(TAG, "Received scheduled action: " + action);

        // Handle device boot - reschedule all alarms
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            handleBootCompleted(context);
            return;
        }

        // Handle scheduled modes
        if (ScheduleManager.ACTION_SUNSET_MODE.equals(action)) {
            int brightness = intent.getIntExtra("brightness", 80);
            boolean isTest = intent.getBooleanExtra("test", false);
            executeSunsetMode(context, brightness, isTest);

        } else if (ScheduleManager.ACTION_BEDTIME_MODE.equals(action)) {
            int brightness = intent.getIntExtra("brightness", 20);
            boolean isTest = intent.getBooleanExtra("test", false);
            executeBedtimeMode(context, brightness, isTest);

        } else {
            Log.w(TAG, "Unknown action received: " + action);
        }
    }

    /**
     * Handle device boot - restore scheduling
     */
    private void handleBootCompleted(Context context) {
        Log.d(TAG, "Device boot completed - restoring scheduling");

        ScheduleManager scheduleManager = new ScheduleManager(context);
        scheduleManager.restoreSchedulingFromPreferences();
    }

    /**
     * Execute sunset mode automatically
     */
    private void executeSunsetMode(Context context, int brightness, boolean isTest) {
        Log.d(TAG, "Executing sunset mode (brightness: " + brightness + "%, test: " + isTest + ")");

        // Load device ID from preferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(PREF_DEVICE_ID, "");

        if (deviceId.isEmpty()) {
            Log.e(TAG, "Cannot execute sunset mode - device ID not configured");
            showErrorNotification(context, "Sunset Mode Error", "Device ID not configured");
            return;
        }

        // Connect to Tuya and execute sunset mode
        TuyaCloudApiService tuyaService = new TuyaCloudApiService();
        tuyaService.setDeviceId(deviceId);

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                Log.d(TAG, "Connected to device for sunset mode: " + deviceName);

                // Set sunset brightness
                tuyaService.setBrightness(brightness);

                // Show success notification
                String title = isTest ? "🌅 Test Sunset Mode" : "🌅 Sunset Mode Activated";
                String message = "Lights automatically dimmed to " + brightness + "% for sunset";
                showSuccessNotification(context, title, message, NOTIFICATION_ID_SUNSET);

                // Save current state
                updateLightSettingsInPreferences(context, brightness, false);
            }

            @Override
            public void onDeviceDisconnected() {
                Log.w(TAG, "Device disconnected during sunset mode execution");
                showErrorNotification(context, "Sunset Mode Warning", "Device disconnected during execution");
            }

            @Override
            public void onBrightnessChanged(int actualBrightness) {
                Log.d(TAG, "Sunset mode: brightness successfully set to " + actualBrightness + "%");

                // Disconnect after successful execution
                tuyaService.disconnect();
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                Log.d(TAG, "Sunset mode: light state changed to " + (isOn ? "ON" : "OFF"));
            }

            @Override
            public void onColorChanged(String hexColor) {
                Log.d(TAG, "Sunset mode: color changed to " + hexColor);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error during sunset mode execution: " + error);
                showErrorNotification(context, "Sunset Mode Error", "Failed to connect: " + error);

                // Clean up
                tuyaService.disconnect();
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Sunset mode success: " + message);
            }
        });

        // Connect to execute the mode
        tuyaService.connect();
    }

    /**
     * Execute bedtime mode automatically
     */
    private void executeBedtimeMode(Context context, int brightness, boolean isTest) {
        Log.d(TAG, "Executing bedtime mode (brightness: " + brightness + "%, test: " + isTest + ")");

        // Load device ID from preferences
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String deviceId = prefs.getString(PREF_DEVICE_ID, "");

        if (deviceId.isEmpty()) {
            Log.e(TAG, "Cannot execute bedtime mode - device ID not configured");
            showErrorNotification(context, "Bedtime Mode Error", "Device ID not configured");
            return;
        }

        // Connect to Tuya and execute bedtime mode
        TuyaCloudApiService tuyaService = new TuyaCloudApiService();
        tuyaService.setDeviceId(deviceId);

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                Log.d(TAG, "Connected to device for bedtime mode: " + deviceName);

                // Set bedtime brightness
                tuyaService.setBrightness(brightness);

                // Show success notification
                String title = isTest ? "🌙 Test Bedtime Mode" : "🌙 Bedtime Mode Activated";
                String message = "Lights automatically dimmed to " + brightness + "% for bedtime";
                showSuccessNotification(context, title, message, NOTIFICATION_ID_BEDTIME);

                // Save current state
                updateLightSettingsInPreferences(context, brightness, true);
            }

            @Override
            public void onDeviceDisconnected() {
                Log.w(TAG, "Device disconnected during bedtime mode execution");
                showErrorNotification(context, "Bedtime Mode Warning", "Device disconnected during execution");
            }

            @Override
            public void onBrightnessChanged(int actualBrightness) {
                Log.d(TAG, "Bedtime mode: brightness successfully set to " + actualBrightness + "%");

                // Disconnect after successful execution
                tuyaService.disconnect();
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                Log.d(TAG, "Bedtime mode: light state changed to " + (isOn ? "ON" : "OFF"));
            }

            @Override
            public void onColorChanged(String hexColor) {
                Log.d(TAG, "Bedtime mode: color changed to " + hexColor);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error during bedtime mode execution: " + error);
                showErrorNotification(context, "Bedtime Mode Error", "Failed to connect: " + error);

                // Clean up
                tuyaService.disconnect();
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Bedtime mode success: " + message);
            }
        });

        // Connect to execute the mode
        tuyaService.connect();
    }

    /**
     * Update light settings in preferences to reflect current state
     */
    private void updateLightSettingsInPreferences(Context context, int brightness, boolean isDimmed) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putInt("current_brightness", brightness);
        editor.putBoolean("is_dimmed", isDimmed);
        editor.putLong("last_auto_adjustment", System.currentTimeMillis());

        editor.apply();

        Log.d(TAG, "Light settings updated in preferences (brightness: " + brightness + "%, dimmed: " + isDimmed + ")");
    }

    /**
     * Show success notification for scheduled mode activation
     */
    private void showSuccessNotification(Context context, String title, String message, int notificationId) {
        createNotificationChannel(context);

        // Create intent to open app when notification is tapped
        Intent appIntent = new Intent(context, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_smart_bulb) // Make sure this icon exists
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message));

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.notify(notificationId, builder.build());
        }

        Log.d(TAG, "Success notification shown: " + title);
    }

    /**
     * Show error notification for failed mode activation
     */
    private void showErrorNotification(Context context, String title, String message) {
        createNotificationChannel(context);

        // Create intent to open app when notification is tapped
        Intent appIntent = new Intent(context, MainActivity.class);
        appIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                9999, // Different ID for error notifications
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert) // System error icon
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message));

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
        }

        Log.e(TAG, "Error notification shown: " + title + " - " + message);
    }

    /**
     * Create notification channel for Android 8.0+ compatibility
     */
    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (notificationManager != null) {
                NotificationChannel existingChannel = notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);

                if (existingChannel == null) {
                    NotificationChannel channel = new NotificationChannel(
                            NOTIFICATION_CHANNEL_ID,
                            NOTIFICATION_CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_DEFAULT
                    );

                    channel.setDescription("Notifications for automatic smart bulb scheduling");
                    channel.enableLights(true);
                    channel.setLightColor(0xFF8BC34A); // Green light
                    channel.enableVibration(false);

                    notificationManager.createNotificationChannel(channel);
                    Log.d(TAG, "Notification channel created");
                }
            }
        }
    }

    /**
     * Get current timestamp for logging
     */
    private String getCurrentTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
    }
}