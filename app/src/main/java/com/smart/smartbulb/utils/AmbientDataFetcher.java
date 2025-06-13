// AmbientDataFetcher.java
package com.smart.smartbulb.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.smart.smartbulb.api.AmbientLightApiService;

/**
 * Utility class for continuous ambient light data fetching
 * Handles periodic API calls every 5 seconds
 */
public class AmbientDataFetcher {

    private static final String TAG = "AmbientDataFetcher";
    private static final long FETCH_INTERVAL = 5000; // 5 seconds

    public interface AmbientDataListener {
        void onDataReceived(AmbientLightApiService.AmbientLightData data);
        void onError(String error);
        void onFetchStarted();
    }

    private final Handler handler;
    private final AmbientDataListener listener;
    private boolean isRunning = false;
    private boolean isPaused = false;
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    private final Runnable fetchTask = new Runnable() {
        @Override
        public void run() {
            if (isRunning && !isPaused) {
                fetchAmbientData();
                // Schedule next fetch
                handler.postDelayed(this, FETCH_INTERVAL);
            }
        }
    };

    public AmbientDataFetcher(AmbientDataListener listener) {
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Start continuous fetching every 5 seconds
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            isPaused = false;
            consecutiveErrors = 0;
            Log.d(TAG, "Starting continuous ambient data fetching (5-second interval)");

            // Immediate first fetch
            fetchAmbientData();

            // Schedule periodic fetches
            handler.postDelayed(fetchTask, FETCH_INTERVAL);
        }
    }

    /**
     * Stop continuous fetching
     */
    public void stop() {
        if (isRunning) {
            isRunning = false;
            handler.removeCallbacks(fetchTask);
            Log.d(TAG, "Stopped continuous ambient data fetching");
        }
    }

    /**
     * Pause fetching (useful when app goes to background)
     */
    public void pause() {
        isPaused = true;
        Log.d(TAG, "Paused ambient data fetching");
    }

    /**
     * Resume fetching
     */
    public void resume() {
        if (isRunning && isPaused) {
            isPaused = false;
            Log.d(TAG, "Resumed ambient data fetching");

            // Immediate fetch on resume
            fetchAmbientData();

            // Restart periodic fetching
            handler.postDelayed(fetchTask, FETCH_INTERVAL);
        }
    }

    /**
     * Force an immediate fetch (doesn't interrupt the schedule)
     */
    public void fetchImmediately() {
        if (isRunning) {
            Log.d(TAG, "Forcing immediate ambient data fetch");
            fetchAmbientData();
        }
    }

    /**
     * Check if fetcher is running
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Check if fetcher is paused
     */
    public boolean isPaused() {
        return isPaused;
    }

    /**
     * Get current fetch interval
     */
    public long getFetchInterval() {
        return FETCH_INTERVAL;
    }

    /**
     * Get status information
     */
    public String getStatusInfo() {
        StringBuilder status = new StringBuilder();
        status.append("Fetcher: ");
        status.append(isRunning ? "Running" : "Stopped");
        if (isRunning) {
            status.append(isPaused ? " (Paused)" : " (Active)");
            status.append(", Interval: ").append(FETCH_INTERVAL / 1000).append("s");
            status.append(", Errors: ").append(consecutiveErrors);
        }
        return status.toString();
    }

    private void fetchAmbientData() {
        // Notify listener that fetch is starting
        if (listener != null) {
            listener.onFetchStarted();
        }

        Log.d(TAG, "Fetching ambient light data...");

        AmbientLightApiService.fetchAmbientLightData(new AmbientLightApiService.AmbientLightCallback() {
            @Override
            public void onSuccess(AmbientLightApiService.AmbientLightData data) {
                Log.d(TAG, String.format("Successfully fetched ambient data: %.1f%% ambient, %.1f lux",
                        data.getPercentage(), data.getLux()));

                // Reset error counter on success
                consecutiveErrors = 0;

                // Notify listener
                if (listener != null) {
                    listener.onDataReceived(data);
                }
            }

            @Override
            public void onError(String error) {
                consecutiveErrors++;
                Log.e(TAG, "Error fetching ambient data (attempt " + consecutiveErrors + "): " + error);

                // If too many consecutive errors, pause fetching to avoid spam
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "Too many consecutive errors, pausing fetcher for 30 seconds");
                    pauseTemporarily();
                }

                // Notify listener
                if (listener != null) {
                    listener.onError("Fetch error (#" + consecutiveErrors + "): " + error);
                }
            }
        });
    }

    /**
     * Temporarily pause fetching for 30 seconds after multiple errors
     */
    private void pauseTemporarily() {
        isPaused = true;

        // Resume after 30 seconds
        handler.postDelayed(() -> {
            if (isRunning) {
                consecutiveErrors = 0;
                resume();
                Log.d(TAG, "Resuming fetcher after temporary pause");
            }
        }, 30000); // 30 seconds
    }
}
