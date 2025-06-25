package com.smart.smartbulb.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.smart.smartbulb.models.LightSettings;
import com.smart.smartbulb.receivers.ScheduleBroadcastReceiver;

import java.util.Calendar;

/**
 * Manages automatic scheduling of sunset and bedtime modes for smart bulb
 */
public class ScheduleManager {
    private static final String TAG = "ScheduleManager";
    private static final String PREFS_NAME = "SmartBulbPrefs";

    // Request codes for different alarms
    private static final int SUNSET_REQUEST_CODE = 1001;
    private static final int BEDTIME_REQUEST_CODE = 1002;

    // Actions for broadcast intents
    public static final String ACTION_SUNSET_MODE = "com.smart.smartbulb.SUNSET_MODE";
    public static final String ACTION_BEDTIME_MODE = "com.smart.smartbulb.BEDTIME_MODE";

    // Preferences keys for schedule persistence
    private static final String PREF_SUNSET_TIME = "sunset_time";
    private static final String PREF_BEDTIME = "bedtime";
    private static final String PREF_AUTO_SCHEDULE_ENABLED = "auto_schedule_enabled";
    private static final String PREF_DEVICE_ID = "device_id";

    private Context context;
    private AlarmManager alarmManager;
    private SharedPreferences preferences;

    public ScheduleManager(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Log.d(TAG, "ScheduleManager initialized");
    }

    /**
     * Schedule automatic modes based on current settings
     */
    public void scheduleAutoModes(LightSettings settings) {
        if (settings == null) {
            Log.w(TAG, "Cannot schedule - settings are null");
            return;
        }

        // Save settings to preferences for persistence
        saveSettingsToPreferences(settings);

        if (!settings.isAutoScheduleEnabled()) {
            Log.d(TAG, "Auto schedule disabled - canceling all alarms");
            cancelScheduledAlarms();
            return;
        }

        // Validate required settings
        if (settings.getSunsetTime() == null || settings.getBedtime() == null) {
            Log.w(TAG, "Cannot schedule - sunset time or bedtime is null");
            return;
        }

        // Schedule both modes
        scheduleSunsetMode(settings.getSunsetTime());
        scheduleBedtimeMode(settings.getBedtime());

        Log.d(TAG, "Auto modes scheduled successfully");
    }

    /**
     * Schedule sunset mode at specified time
     */
    private void scheduleSunsetMode(String sunsetTime) {
        try {
            Calendar calendar = getNextScheduleTime(sunsetTime);

            Intent intent = new Intent(context, ScheduleBroadcastReceiver.class);
            intent.setAction(ACTION_SUNSET_MODE);
            intent.putExtra("brightness", 80);
            intent.putExtra("mode", "sunset");

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    SUNSET_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Set repeating daily alarm
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
            );

            Log.d(TAG, "Sunset mode scheduled for: " + sunsetTime + " (next: " + calendar.getTime() + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error scheduling sunset mode", e);
        }
    }

    /**
     * Schedule bedtime mode at specified time
     */
    private void scheduleBedtimeMode(String bedtimeTime) {
        try {
            Calendar calendar = getNextScheduleTime(bedtimeTime);

            Intent intent = new Intent(context, ScheduleBroadcastReceiver.class);
            intent.setAction(ACTION_BEDTIME_MODE);
            intent.putExtra("brightness", 20);
            intent.putExtra("mode", "bedtime");

            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    BEDTIME_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            // Set repeating daily alarm
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
            );

            Log.d(TAG, "Bedtime mode scheduled for: " + bedtimeTime + " (next: " + calendar.getTime() + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error scheduling bedtime mode", e);
        }
    }

    /**
     * Calculate next occurrence of the specified time
     */
    private Calendar getNextScheduleTime(String timeString) {
        String[] timeParts = timeString.split(":");
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);

        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If the time has already passed today, schedule for tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }

        return calendar;
    }

    /**
     * Cancel all scheduled alarms
     */
    public void cancelScheduledAlarms() {
        try {
            // Cancel sunset alarm
            Intent sunsetIntent = new Intent(context, ScheduleBroadcastReceiver.class);
            sunsetIntent.setAction(ACTION_SUNSET_MODE);
            PendingIntent sunsetPendingIntent = PendingIntent.getBroadcast(
                    context,
                    SUNSET_REQUEST_CODE,
                    sunsetIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(sunsetPendingIntent);

            // Cancel bedtime alarm
            Intent bedtimeIntent = new Intent(context, ScheduleBroadcastReceiver.class);
            bedtimeIntent.setAction(ACTION_BEDTIME_MODE);
            PendingIntent bedtimePendingIntent = PendingIntent.getBroadcast(
                    context,
                    BEDTIME_REQUEST_CODE,
                    bedtimeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarmManager.cancel(bedtimePendingIntent);

            Log.d(TAG, "All scheduled alarms canceled");

        } catch (Exception e) {
            Log.e(TAG, "Error canceling scheduled alarms", e);
        }
    }

    /**
     * Save settings to preferences for persistence across app restarts
     */
    private void saveSettingsToPreferences(LightSettings settings) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREF_SUNSET_TIME, settings.getSunsetTime());
        editor.putString(PREF_BEDTIME, settings.getBedtime());
        editor.putBoolean(PREF_AUTO_SCHEDULE_ENABLED, settings.isAutoScheduleEnabled());
        editor.apply();

        Log.d(TAG, "Settings saved to preferences");
    }

    /**
     * Restore scheduling from saved preferences (useful after app restart)
     */
    public void restoreSchedulingFromPreferences() {
        boolean autoScheduleEnabled = preferences.getBoolean(PREF_AUTO_SCHEDULE_ENABLED, false);

        if (!autoScheduleEnabled) {
            Log.d(TAG, "Auto schedule not enabled in preferences");
            return;
        }

        String sunsetTime = preferences.getString(PREF_SUNSET_TIME, null);
        String bedtime = preferences.getString(PREF_BEDTIME, null);
        String deviceId = preferences.getString(PREF_DEVICE_ID, null);

        if (sunsetTime == null || bedtime == null || deviceId == null) {
            Log.w(TAG, "Cannot restore scheduling - missing required preferences");
            return;
        }

        // Create settings object from preferences
        LightSettings settings = new LightSettings();
        settings.setSunsetTime(sunsetTime);
        settings.setBedtime(bedtime);
        settings.setAutoScheduleEnabled(true);

        // Reschedule
        scheduleAutoModes(settings);

        Log.d(TAG, "Scheduling restored from preferences");
    }

    /**
     * Check if automatic scheduling is currently enabled
     */
    public boolean isAutoScheduleEnabled() {
        return preferences.getBoolean(PREF_AUTO_SCHEDULE_ENABLED, false);
    }

    /**
     * Get current sunset time from preferences
     */
    public String getSunsetTime() {
        return preferences.getString(PREF_SUNSET_TIME, "18:30");
    }

    /**
     * Get current bedtime from preferences
     */
    public String getBedtime() {
        return preferences.getString(PREF_BEDTIME, "22:00");
    }

    /**
     * Update only sunset time and reschedule if needed
     */
    public void updateSunsetTime(String newTime) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREF_SUNSET_TIME, newTime);
        editor.apply();

        if (isAutoScheduleEnabled()) {
            // Reschedule with new time
            LightSettings settings = getCurrentSettingsFromPreferences();
            scheduleAutoModes(settings);
        }

        Log.d(TAG, "Sunset time updated to: " + newTime);
    }

    /**
     * Update only bedtime and reschedule if needed
     */
    public void updateBedtime(String newTime) {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(PREF_BEDTIME, newTime);
        editor.apply();

        if (isAutoScheduleEnabled()) {
            // Reschedule with new time
            LightSettings settings = getCurrentSettingsFromPreferences();
            scheduleAutoModes(settings);
        }

        Log.d(TAG, "Bedtime updated to: " + newTime);
    }

    /**
     * Create LightSettings object from current preferences
     */
    private LightSettings getCurrentSettingsFromPreferences() {
        LightSettings settings = new LightSettings();
        settings.setSunsetTime(getSunsetTime());
        settings.setBedtime(getBedtime());
        settings.setAutoScheduleEnabled(isAutoScheduleEnabled());
        return settings;
    }

    /**
     * Get scheduling status for debugging
     */
    public String getSchedulingStatus() {
        StringBuilder status = new StringBuilder();
        status.append("ScheduleManager Status:\n");
        status.append("- Auto schedule enabled: ").append(isAutoScheduleEnabled()).append("\n");
        status.append("- Sunset time: ").append(getSunsetTime()).append("\n");
        status.append("- Bedtime: ").append(getBedtime()).append("\n");

        if (isAutoScheduleEnabled()) {
            Calendar nextSunset = getNextScheduleTime(getSunsetTime());
            Calendar nextBedtime = getNextScheduleTime(getBedtime());
            status.append("- Next sunset: ").append(nextSunset.getTime()).append("\n");
            status.append("- Next bedtime: ").append(nextBedtime.getTime()).append("\n");
        }

        return status.toString();
    }

    /**
     * Test immediate execution of sunset mode (for debugging)
     */
    public void testSunsetMode() {
        Intent intent = new Intent(context, ScheduleBroadcastReceiver.class);
        intent.setAction(ACTION_SUNSET_MODE);
        intent.putExtra("brightness", 80);
        intent.putExtra("mode", "sunset");
        intent.putExtra("test", true);

        context.sendBroadcast(intent);
        Log.d(TAG, "Test sunset mode triggered");
    }

    /**
     * Test immediate execution of bedtime mode (for debugging)
     */
    public void testBedtimeMode() {
        Intent intent = new Intent(context, ScheduleBroadcastReceiver.class);
        intent.setAction(ACTION_BEDTIME_MODE);
        intent.putExtra("brightness", 20);
        intent.putExtra("mode", "bedtime");
        intent.putExtra("test", true);

        context.sendBroadcast(intent);
        Log.d(TAG, "Test bedtime mode triggered");
    }

    /**
     * Force immediate rescheduling of all alarms
     */
    public void forceReschedule() {
        Log.d(TAG, "Forcing reschedule of all alarms");

        if (isAutoScheduleEnabled()) {
            LightSettings settings = getCurrentSettingsFromPreferences();
            scheduleAutoModes(settings);
        } else {
            cancelScheduledAlarms();
        }
    }
}