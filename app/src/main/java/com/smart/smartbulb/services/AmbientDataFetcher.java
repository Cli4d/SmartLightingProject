package com.smart.smartbulb.services;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.lang.ref.WeakReference;

public class AmbientDataFetcher {
    private static final String TAG = "AmbientDataFetcher";

    // ThingsBoard Configuration
    private static final String THINGSBOARD_URL = "https://thingsboard.cloud";
    private static final String USERNAME = "smartbulbproject@gmail.com";
    private static final String PASSWORD = "smartbulb2024";
    private static final String DEVICE_ID = "8c6b8eb0-29dd-11ef-b0bd-c5fc5b5e0e42";

    // Service state
    private String authToken;
    private boolean isAuthenticated = false;
    private boolean isFetching = false;
    private boolean isDestroyed = false;

    // Threading
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduledExecutor;
    private final Handler mainHandler;

    // Callback handling
    private WeakReference<AmbientCallback> callbackRef;

    // Background tasks
    private ScheduledFuture<?> fetchingTask;
    private Future<?> authTask;

    public interface AmbientCallback {
        void onAmbientDataReceived(int percentage, double lux);
        void onError(String error);
    }

    public AmbientDataFetcher() {
        this.executorService = Executors.newFixedThreadPool(2);
        this.scheduledExecutor = Executors.newScheduledThreadPool(1);
        this.mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "AmbientDataFetcher initialized");
    }

    public void setCallback(AmbientCallback callback) {
        this.callbackRef = new WeakReference<>(callback);
        Log.d(TAG, "Callback set");
    }

    public void startFetching() {
        if (isDestroyed) {
            Log.w(TAG, "Service destroyed, cannot start fetching");
            return;
        }

        if (isFetching) {
            Log.d(TAG, "Already fetching data");
            return;
        }

        Log.d(TAG, "Starting ambient data fetching");
        isFetching = true;

        // First authenticate, then start periodic fetching
        authTask = executorService.submit(() -> {
            if (authenticate()) {
                startPeriodicFetching();
            } else {
                isFetching = false;
                notifyError("Authentication failed");
            }
        });
    }

    public void stopFetching() {
        isFetching = false;

        // Cancel periodic fetching
        if (fetchingTask != null && !fetchingTask.isDone()) {
            fetchingTask.cancel(true);
        }

        // Cancel authentication task
        if (authTask != null && !authTask.isDone()) {
            authTask.cancel(true);
        }

        Log.d(TAG, "Stopped ambient data fetching");
    }

    public void destroy() {
        isDestroyed = true;
        stopFetching();

        // Shutdown executors
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }

        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
        }

        // Clear callback
        callbackRef = null;

        Log.d(TAG, "Service destroyed");
    }

    private boolean authenticate() {
        try {
            Log.d(TAG, "Authenticating with ThingsBoard...");

            String url = THINGSBOARD_URL + "/api/auth/login";

            // Create login payload
            JSONObject loginData = new JSONObject();
            loginData.put("username", USERNAME);
            loginData.put("password", PASSWORD);

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Send login data
            try (OutputStream os = connection.getOutputStream()) {
                os.write(loginData.toString().getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Login response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse response to get token
                JSONObject jsonResponse = new JSONObject(response.toString());
                authToken = jsonResponse.getString("token");
                isAuthenticated = true;

                Log.d(TAG, "Authentication successful, token obtained");
                return true;

            } else {
                Log.e(TAG, "Authentication failed with code: " + responseCode);
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Authentication error", e);
            return false;
        }
    }

    private void startPeriodicFetching() {
        if (isDestroyed || !isFetching) return;

        Log.d(TAG, "Starting periodic ambient data fetching (5-second intervals)");

        fetchingTask = scheduledExecutor.scheduleWithFixedDelay(() -> {
            if (!isDestroyed && isFetching && isAuthenticated) {
                fetchAmbientData();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void fetchAmbientData() {
        try {
            // Get current timestamp for telemetry query
            long endTime = System.currentTimeMillis();
            long startTime = endTime - (60 * 1000); // Last minute

            String url = String.format(
                    "%s/api/plugins/telemetry/DEVICE/%s/values/timeseries?keys=time,lux,percentage,rms&startTs=%d&endTs=%d",
                    THINGSBOARD_URL, DEVICE_ID, startTime, endTime
            );

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-Authorization", "Bearer " + authToken);

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Telemetry response code: " + responseCode);

            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.d(TAG, "Successfully fetched telemetry data");
                Log.d(TAG, "Response data: " + response.toString());

                parseAmbientData(response.toString());

            } else if (responseCode == 401) {
                Log.w(TAG, "Token expired, re-authenticating");
                isAuthenticated = false;
                if (authenticate()) {
                    fetchAmbientData(); // Retry with new token
                }
            } else {
                Log.e(TAG, "Telemetry fetch failed with code: " + responseCode);
                notifyError("Failed to fetch ambient data");
            }

        } catch (Exception e) {
            Log.e(TAG, "Fetch ambient data error", e);
            notifyError("Network error: " + e.getMessage());
        }
    }

    private void parseAmbientData(String responseData) {
        try {
            Log.d(TAG, "Parsing JSON response: " + responseData);

            JSONObject jsonResponse = new JSONObject(responseData);

            // Extract the latest values
            double lux = 1.67; // Default value
            int percentage = 50; // Default percentage

            // Parse lux data
            if (jsonResponse.has("lux")) {
                JSONArray luxArray = jsonResponse.getJSONArray("lux");
                if (luxArray.length() > 0) {
                    JSONObject latestLux = luxArray.getJSONObject(luxArray.length() - 1);
                    String luxValue = latestLux.getString("value");
                    lux = Double.parseDouble(luxValue);
                    Log.d(TAG, "Parsed lux: " + lux);
                }
            }

            // Parse percentage data
            if (jsonResponse.has("percentage")) {
                JSONArray percentageArray = jsonResponse.getJSONArray("percentage");
                if (percentageArray.length() > 0) {
                    JSONObject latestPercentage = percentageArray.getJSONObject(percentageArray.length() - 1);
                    String percentageValue = latestPercentage.getString("value");
                    percentage = (int) (Double.parseDouble(percentageValue) * 100);
                    Log.d(TAG, "Parsed percentage: " + percentage);
                }
            }

            // Get timestamp for logging
            String timestamp = "Unknown";
            if (jsonResponse.has("time")) {
                JSONArray timeArray = jsonResponse.getJSONArray("time");
                if (timeArray.length() > 0) {
                    JSONObject latestTime = timeArray.getJSONObject(timeArray.length() - 1);
                    timestamp = latestTime.getString("value");
                    Log.d(TAG, "Parsed timestamp: " + timestamp);
                }
            }

            final double finalLux = lux;
            final int finalPercentage = Math.max(0, Math.min(100, percentage));

            Log.d(TAG, String.format("Final parsed data - Lux: %.2f, Percentage: %d%%", finalLux, finalPercentage));

            // Notify callback with parsed data
            notifyCallback(callback -> callback.onAmbientDataReceived(finalPercentage, finalLux));

        } catch (Exception e) {
            Log.e(TAG, "Parse ambient data error", e);

            // Provide fallback data to keep the app functional
            notifyCallback(callback -> callback.onAmbientDataReceived(50, 1.67));
        }
    }

    private void notifyCallback(CallbackAction action) {
        if (isDestroyed) return;

        AmbientCallback callback = callbackRef != null ? callbackRef.get() : null;
        if (callback != null) {
            mainHandler.post(() -> {
                try {
                    action.execute(callback);
                } catch (Exception e) {
                    Log.e(TAG, "Callback execution error", e);
                }
            });
        }
    }

    private void notifyError(String error) {
        notifyCallback(callback -> callback.onError(error));
    }

    // Public utility methods
    public boolean isCurrentlyFetching() {
        return isFetching && isAuthenticated;
    }

    public boolean isServiceReady() {
        return !isDestroyed && isAuthenticated;
    }

    private interface CallbackAction {
        void execute(AmbientCallback callback);
    }
}