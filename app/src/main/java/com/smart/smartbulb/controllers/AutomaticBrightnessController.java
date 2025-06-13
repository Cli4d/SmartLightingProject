package com.smart.smartbulb.controllers;

// AutomaticBrightnessController.java


import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.smart.smartbulb.api.AmbientLightApiService;
import com.smart.smartbulb.models.LightSettings;
import com.smart.smartbulb.utils.LightBrightnessManager;

/**
 * Controls automatic brightness adjustment based on ambient light
 * Maintains the ambient + bulb = 100% relationship
 */
public class AutomaticBrightnessController {

    private static final String TAG = "AutoBrightnessController";
    private static final long UPDATE_INTERVAL = 30000; // 30 seconds

    public interface AutoBrightnessListener {
        void onBrightnessUpdated(int newBulbBrightness, int ambientPercentage, String reason);
        void onOptimalBrightnessAchieved(int bulbBrightness, int ambientPercentage);
        void onError(String error);
    }

    private final LightSettings lightSettings;
    private final AutoBrightnessListener listener;
    private final Handler handler;
    private boolean isRunning = false;
    private boolean isAutoModeEnabled = true;

    private final Runnable updateTask = new Runnable() {
        @Override
        public void run() {
            if (isRunning && isAutoModeEnabled) {
                fetchAmbientAndUpdateBrightness();
                // Schedule next update
                handler.postDelayed(this, UPDATE_INTERVAL);
            }
        }
    };

    public AutomaticBrightnessController(LightSettings lightSettings, AutoBrightnessListener listener) {
        this.lightSettings = lightSettings;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start automatic brightness control
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            Log.d(TAG, "Starting automatic brightness control");

            // Immediate first update
            fetchAmbientAndUpdateBrightness();

            // Schedule periodic updates
            handler.postDelayed(updateTask, UPDATE_INTERVAL);
        }
    }

    /**
     * Stop automatic brightness control
     */
    public void stop() {
        if (isRunning) {
            isRunning = false;
            handler.removeCallbacks(updateTask);
            Log.d(TAG, "Stopped automatic brightness control");
        }
    }

    /**
     * Enable or disable auto mode
     */
    public void setAutoModeEnabled(boolean enabled) {
        this.isAutoModeEnabled = enabled;
        Log.d(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));

        if (enabled && isRunning) {
            // Trigger immediate update when re-enabling
            fetchAmbientAndUpdateBrightness();
        }
    }

    /**
     * Check if auto mode is enabled
     */
    public boolean isAutoModeEnabled() {
        return isAutoModeEnabled;
    }

    /**
     * Check if controller is running
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Manually trigger a brightness update
     */
    public void forceUpdate() {
        if (isRunning) {
            fetchAmbientAndUpdateBrightness();
        }
    }

    /**
     * Fetch ambient light data and update bulb brightness
     */
    private void fetchAmbientAndUpdateBrightness() {
        // Only adjust if bulb is on
        if (!lightSettings.isBulbOn()) {
            Log.d(TAG, "Bulb is off, skipping brightness update");
            return;
        }

        AmbientLightApiService.fetchAmbientLightData(new AmbientLightApiService.AmbientLightCallback() {
            @Override
            public void onSuccess(AmbientLightApiService.AmbientLightData data) {
                processBrightnessUpdate(data);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Failed to fetch ambient light data: " + error);
                if (listener != null) {
                    listener.onError("Auto-brightness update failed: " + error);
                }
            }
        });
    }

    /**
     * Process brightness update based on ambient data
     */
    private void processBrightnessUpdate(AmbientLightApiService.AmbientLightData ambientData) {
        if (!isAutoModeEnabled) {
            Log.d(TAG, "Auto mode disabled, skipping brightness update");
            return;
        }

        int ambientPercentage = ambientData.getPercentageAsInt();
        int currentBrightness = lightSettings.getBrightness();
        int optimalBrightness = LightBrightnessManager.calculateOptimalBulbBrightness(ambientPercentage);

        // Check if adjustment is needed (with tolerance)
        int brightnessDifference = Math.abs(currentBrightness - optimalBrightness);

        if (brightnessDifference > 5) { // 5% tolerance
            // Update brightness using the manager
            LightBrightnessManager.updateBulbBrightnessFromAmbient(
                    ambientData,
                    lightSettings,
                    new LightBrightnessManager.BrightnessUpdateListener() {
                        @Override
                        public void onBrightnessCalculated(int newBulbBrightness, int ambientPercentage, String calculationReason) {
                            Log.d(TAG, String.format("Auto-adjusted brightness: %d%% (ambient: %d%%) - %s",
                                    newBulbBrightness, ambientPercentage, calculationReason));

                            if (listener != null) {
                                listener.onBrightnessUpdated(newBulbBrightness, ambientPercentage, calculationReason);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Brightness calculation error: " + error);
                            if (listener != null) {
                                listener.onError(error);
                            }
                        }
                    }
            );
        } else {
            // Already optimal
            Log.d(TAG, String.format("Brightness already optimal: %d%% (ambient: %d%%)",
                    currentBrightness, ambientPercentage));

            if (listener != null) {
                listener.onOptimalBrightnessAchieved(currentBrightness, ambientPercentage);
            }
        }
    }

    /**
     * Get status information
     */
    public String getStatusInfo() {
        StringBuilder status = new StringBuilder();
        status.append("Auto Brightness: ");
        status.append(isRunning ? "Running" : "Stopped");
        status.append(", Mode: ");
        status.append(isAutoModeEnabled ? "Auto" : "Manual");

        if (lightSettings != null) {
            status.append(", Current: ").append(lightSettings.getBrightness()).append("%");
        }

        return status.toString();
    }
}