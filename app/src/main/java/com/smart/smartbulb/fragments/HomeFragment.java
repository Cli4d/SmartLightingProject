// HomeFragment.java (Updated with comprehensive safety checks)
package com.smart.smartbulb.fragments;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.smart.smartbulb.R;
import com.smart.smartbulb.api.AmbientLightApiService;
import com.smart.smartbulb.models.LightSettings;
import com.smart.smartbulb.services.TuyaCloudApiService;
import com.smart.smartbulb.utils.AmbientDataFetcher;
import com.smart.smartbulb.utils.BrightnessDisplayHelper;
import com.smart.smartbulb.utils.LightBrightnessManager;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

// HomeFragment.java (Complete Updated Version)

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";

    // Fragment state
    private View rootView;
    private LightSettings lightSettings;
    private Consumer<LightSettings> callback;

    // Background services
    private volatile Thread timeUpdater;
    private AmbientDataFetcher ambientDataFetcher;
    private TuyaCloudApiService tuyaCloudService;

    // UI Components
    private TextView textTime;
    private TextView textBrightness;
    private TextView textAmbientLight;
    private TextView textBrightnessSummary;
    private TextView textTuyaStatus;
    private SwitchMaterial switchBulb;
    private SwitchMaterial switchAutoMode;
    private SeekBar sliderBrightness;
    private Button buttonDim;
    private Button buttonBrighten;
    private Button buttonCancelDimming;
    private Button buttonOptimize;
    private FrameLayout imageBulb;
    private ProgressBar progressAmbientLight;
    private MaterialCardView cardDimming;
    private MaterialCardView cardBrightnessSummary;
    private MaterialCardView cardTuyaStatus;

    // State tracking
    private AmbientLightApiService.AmbientLightData currentAmbientData;
    private volatile boolean isApiDataAvailable = false;
    private volatile boolean isTuyaConnected = false;
    private volatile boolean isAutoModeEnabled = false;
    private volatile boolean isFragmentActive = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_home, container, false);
        Log.d(TAG, "HomeFragment view created with Tuya Cloud API integration");
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "Initializing HomeFragment with Tuya Cloud API and ambient light integration");

        isFragmentActive = true;

        // Initialize components
        initializeLightSettings();
        initializeViews();
        setupUIEventHandlers();
        initializeTuyaCloudService();
        initializeAmbientDataFetcher();
        startTimeUpdater();

        // Initial UI update
        safeUpdateUI(() -> updateAllUI());
    }

    private void initializeLightSettings() {
        if (getArguments() != null) {
            lightSettings = (LightSettings) getArguments().getSerializable("lightSettings");
        }
        if (lightSettings == null) {
            lightSettings = new LightSettings();
        }
    }

    private void initializeViews() {
        if (!isAdded() || rootView == null) {
            Log.w(TAG, "Cannot initialize views - fragment not attached");
            return;
        }

        // Find all UI components
        textTime = rootView.findViewById(R.id.textTime);
        textBrightness = rootView.findViewById(R.id.textBrightness);
        textAmbientLight = rootView.findViewById(R.id.textAmbientLight);
        textBrightnessSummary = rootView.findViewById(R.id.textBrightnessSummary);
        textTuyaStatus = rootView.findViewById(R.id.textTuyaStatus);

        switchBulb = rootView.findViewById(R.id.switchBulb);
        switchAutoMode = rootView.findViewById(R.id.switchAutoMode);
        sliderBrightness = rootView.findViewById(R.id.sliderBrightness);

        buttonDim = rootView.findViewById(R.id.buttonDim);
        buttonBrighten = rootView.findViewById(R.id.buttonBrighten);
        buttonCancelDimming = rootView.findViewById(R.id.buttonCancelDimming);
        buttonOptimize = rootView.findViewById(R.id.buttonOptimize);

        imageBulb = rootView.findViewById(R.id.imageBulb);
        progressAmbientLight = rootView.findViewById(R.id.progressAmbientLight);

        cardDimming = rootView.findViewById(R.id.cardDimming);
        cardBrightnessSummary = rootView.findViewById(R.id.cardBrightnessSummary);
        cardTuyaStatus = rootView.findViewById(R.id.cardTuyaStatus);
    }

    private void setupUIEventHandlers() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot setup UI handlers - fragment not attached");
            return;
        }

        // Bulb on/off switch - controls Tuya device via Cloud API
        if (switchBulb != null) {
            switchBulb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Log.d(TAG, "Bulb switch toggled: " + isChecked);

                if (isTuyaConnected && tuyaCloudService != null) {
                    tuyaCloudService.setLightState(isChecked);
                }

                lightSettings.setBulbOn(isChecked);

                if (isChecked) {
                    startAmbientDataFetching();
                } else {
                    stopAmbientDataFetching();
                    setAutoModeEnabled(false);
                }

                notifySettingsChanged();
                safeUpdateUI(() -> updateAllUI());
            });
        }

        // Auto mode switch - enables automatic brightness based on ambient light
        if (switchAutoMode != null) {
            switchAutoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Log.d(TAG, "Auto mode toggled: " + isChecked);
                setAutoModeEnabled(isChecked);

                if (isChecked && !isApiDataAvailable) {
                    safeShowSnackbar("Fetching ambient data to enable auto mode...");
                    forceAmbientDataFetch();
                }

                safeUpdateUI(() -> updateAllUI());
            });
        }

        // Brightness slider - directly controls Tuya device via Cloud API
        if (sliderBrightness != null) {
            sliderBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isAdded()) {
                        updateBrightnessDisplay(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    Log.d(TAG, "User started adjusting brightness - disabling auto mode");
                    setAutoModeEnabled(false);
                    if (switchAutoMode != null) {
                        switchAutoMode.setChecked(false);
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (!isAdded()) return;

                    int newBrightness = seekBar.getProgress();
                    Log.d(TAG, "Setting Tuya brightness to: " + newBrightness + "%");

                    // Update Tuya device via Cloud API
                    if (isTuyaConnected && tuyaCloudService != null) {
                        tuyaCloudService.setBrightness(newBrightness);
                    }

                    lightSettings.setBrightness(newBrightness);
                    notifySettingsChanged();
                    safeUpdateUI(() -> updateAllUI());
                }
            });
        }

        // Control buttons
        setupControlButtons();
    }

    private void setupControlButtons() {
        if (buttonDim != null) {
            buttonDim.setOnClickListener(v -> {
                Log.d(TAG, "Dim button clicked");
                setBrightness(20);
                setAutoModeEnabled(false);
                safeShowSnackbar("Lights dimmed to 20%");
            });
        }

        if (buttonBrighten != null) {
            buttonBrighten.setOnClickListener(v -> {
                Log.d(TAG, "Brighten button clicked");
                setBrightness(80);
                setAutoModeEnabled(false);
                safeShowSnackbar("Lights brightened to 80%");
            });
        }

        if (buttonCancelDimming != null) {
            buttonCancelDimming.setOnClickListener(v -> {
                Log.d(TAG, "Cancel dimming clicked");
                lightSettings.cancelDimming();

                // Update Tuya device via Cloud API
                if (isTuyaConnected && tuyaCloudService != null) {
                    tuyaCloudService.setBrightness(lightSettings.getBrightness());
                }

                notifySettingsChanged();
                safeUpdateUI(() -> updateAllUI());
                safeShowSnackbar("Dimming canceled");
            });
        }

        if (buttonOptimize != null) {
            buttonOptimize.setOnClickListener(v -> {
                Log.d(TAG, "Optimize button clicked");
                optimizeBrightness();
            });
        }
    }

    private void initializeTuyaCloudService() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot initialize Tuya service - fragment not attached");
            return;
        }

        Log.d(TAG, "Initializing Tuya Cloud API service");

        tuyaCloudService = new TuyaCloudApiService();
        tuyaCloudService.setDeviceId("bfc64cc8fa223bd6afxqtb"); // TODO: Update with actual device ID

        tuyaCloudService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                if (!isFragmentActive || !isAdded()) {
                    Log.w(TAG, "Fragment not active, skipping connection update");
                    return;
                }

                Log.d(TAG, "Tuya Cloud device connected: " + deviceName);
                isTuyaConnected = true;
                syncFromTuyaCloud();

                safeUpdateUI(() -> {
                    updateTuyaStatus("Connected: " + deviceName);
                    updateAllUI();
                });
                safeShowSnackbar("Tuya Cloud connected: " + deviceName);
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isFragmentActive) return;

                Log.w(TAG, "Tuya Cloud device disconnected");
                isTuyaConnected = false;
                setAutoModeEnabled(false);

                safeUpdateUI(() -> {
                    updateTuyaStatus("Disconnected");
                    updateAllUI();
                });
                safeShowSnackbar("Tuya Cloud disconnected");
            }

            @Override
            public void onBrightnessChanged(int brightness) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Tuya Cloud brightness changed: " + brightness + "%");
                lightSettings.setBrightness(brightness);

                safeUpdateUI(() -> {
                    if (sliderBrightness != null) {
                        sliderBrightness.setProgress(brightness);
                    }
                    updateBrightnessDisplay(brightness);
                    updateBrightnessSummary();
                });
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Tuya Cloud light state changed: " + (isOn ? "ON" : "OFF"));
                lightSettings.setBulbOn(isOn);

                safeUpdateUI(() -> {
                    if (switchBulb != null) {
                        switchBulb.setChecked(isOn);
                    }
                    updateAllUI();
                });

                // Manage ambient fetching based on light state
                if (isOn) {
                    startAmbientDataFetching();
                } else {
                    stopAmbientDataFetching();
                    setAutoModeEnabled(false);
                }
            }

            @Override
            public void onColorChanged(String hexColor) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Tuya Cloud color changed: " + hexColor);
                lightSettings.setColor(hexColor);

                safeUpdateUI(() -> updateBulbVisualization());
            }

            @Override
            public void onError(String error) {
                if (!isFragmentActive) return;

                Log.e(TAG, "Tuya Cloud error: " + error);
                safeUpdateUI(() -> updateTuyaStatus("Error: " + error));
                safeShowSnackbar("Tuya Cloud error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Tuya Cloud success: " + message);
                // Optional: Show success feedback
            }
        });

        // Connect to Tuya Cloud
        tuyaCloudService.connect();
    }

    private void initializeAmbientDataFetcher() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot initialize ambient fetcher - fragment not attached");
            return;
        }

        Log.d(TAG, "Initializing ambient data fetcher (5-second intervals)");

        ambientDataFetcher = new AmbientDataFetcher(new AmbientDataFetcher.AmbientDataListener() {
            @Override
            public void onDataReceived(AmbientLightApiService.AmbientLightData data) {
                if (!isFragmentActive || !isAdded()) {
                    Log.w(TAG, "Fragment not active, skipping ambient data update");
                    return;
                }

                Log.d(TAG, String.format("Fresh ambient data: %.1f%% ambient, %.1f lux",
                        data.getPercentage(), data.getLux()));

                currentAmbientData = data;
                isApiDataAvailable = true;

                // Update UI
                safeUpdateUI(() -> {
                    updateAmbientLightDisplay(data);
                    updateBrightnessSummary();
                });

                // Store in settings
                lightSettings.setAmbientLightLevel(data.getPercentageAsInt());

                // Auto-adjust Tuya brightness if auto mode is enabled
                if (isAutoModeEnabled && isTuyaConnected) {
                    autoAdjustTuyaBrightness(data);
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Ambient data error: " + error);
                // Continue running - let fetcher handle retries
            }

            @Override
            public void onFetchStarted() {
                // Optional: subtle loading indicator
            }
        });
    }

    private void startTimeUpdater() {
        if (timeUpdater != null && timeUpdater.isAlive()) {
            timeUpdater.interrupt();
        }

        timeUpdater = new Thread(() -> {
            while (isFragmentActive && !Thread.currentThread().isInterrupted()) {
                safeUpdateUI(() -> updateCurrentTime());

                try {
                    Thread.sleep(60 * 1000); // Update every minute
                } catch (InterruptedException e) {
                    Log.d(TAG, "Time updater interrupted");
                    break;
                }
            }
        });
        timeUpdater.setDaemon(true);
        timeUpdater.start();
    }

    private void autoAdjustTuyaBrightness(AmbientLightApiService.AmbientLightData ambientData) {
        if (!isAutoModeEnabled || !isTuyaConnected || !lightSettings.isBulbOn() || !isFragmentActive) {
            return;
        }

        int ambientPercentage = ambientData.getPercentageAsInt();
        int currentBrightness = lightSettings.getBrightness();
        int optimalBrightness = LightBrightnessManager.calculateOptimalBulbBrightness(ambientPercentage);

        // Check if adjustment is needed (5% tolerance)
        int difference = Math.abs(currentBrightness - optimalBrightness);

        if (difference > 5) {
            Log.d(TAG, String.format("Auto-adjusting Tuya Cloud: %d%% → %d%% (ambient: %d%%)",
                    currentBrightness, optimalBrightness, ambientPercentage));

            // Update Tuya device via Cloud API
            if (tuyaCloudService != null) {
                tuyaCloudService.setBrightness(optimalBrightness);
            }

            // Update local settings
            lightSettings.setBrightness(optimalBrightness);

            // Update UI
            safeUpdateUI(() -> {
                if (sliderBrightness != null) {
                    sliderBrightness.setProgress(optimalBrightness);
                }
                updateBrightnessDisplay(optimalBrightness);
                updateBrightnessSummary();
            });

            // Show user feedback
            String message = String.format(Locale.getDefault(),
                    "Auto-adjusted: %d%% + %d%% = 100%%",
                    optimalBrightness, ambientPercentage);
            safeShowSnackbar(message);
        }
    }

    private void optimizeBrightness() {
        if (!isAdded()) return;

        if (currentAmbientData != null && isApiDataAvailable && isTuyaConnected) {
            int ambientPercentage = currentAmbientData.getPercentageAsInt();
            int optimalBrightness = LightBrightnessManager.calculateOptimalBulbBrightness(ambientPercentage);

            Log.d(TAG, String.format("Optimizing Tuya Cloud brightness: %d%% (ambient: %d%%)",
                    optimalBrightness, ambientPercentage));

            setBrightness(optimalBrightness);

            String message = String.format(Locale.getDefault(),
                    "Optimized: %d%% + %d%% = 100%%",
                    optimalBrightness, ambientPercentage);
            safeShowSnackbar(message);
        } else {
            if (!isTuyaConnected) {
                safeShowSnackbar("Tuya Cloud not connected");
            } else if (!isApiDataAvailable) {
                safeShowSnackbar("Fetching ambient data...");
                forceAmbientDataFetch();
            }
        }
    }

    private void setBrightness(int brightness) {
        if (!isAdded()) return;

        // Update Tuya device via Cloud API
        if (isTuyaConnected && tuyaCloudService != null) {
            tuyaCloudService.setBrightness(brightness);
        }

        // Update local settings
        lightSettings.setBrightness(brightness);

        // Update UI
        safeUpdateUI(() -> {
            if (sliderBrightness != null) {
                sliderBrightness.setProgress(brightness);
            }
            updateBrightnessDisplay(brightness);
            updateBrightnessSummary();
        });

        notifySettingsChanged();
    }

    private void setAutoModeEnabled(boolean enabled) {
        isAutoModeEnabled = enabled;
        Log.d(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));

        if (enabled && isApiDataAvailable && currentAmbientData != null) {
            // Immediate optimization when enabling auto mode
            autoAdjustTuyaBrightness(currentAmbientData);
        }
    }

    private void syncFromTuyaCloud() {
        if (tuyaCloudService != null && isAdded()) {
            // Sync state from Tuya Cloud API to our settings
            lightSettings.setBulbOn(tuyaCloudService.isLightOn());
            lightSettings.setBrightness(tuyaCloudService.getCurrentBrightness());
            lightSettings.setColor(tuyaCloudService.getCurrentColor());

            Log.d(TAG, "Synced from Tuya Cloud - Light: " + lightSettings.isBulbOn() +
                    ", Brightness: " + lightSettings.getBrightness() + "%");

            // Get latest device status from cloud
            tuyaCloudService.getDeviceStatus();
        }
    }

    private void startAmbientDataFetching() {
        if (ambientDataFetcher != null && !ambientDataFetcher.isRunning() && isFragmentActive) {
            Log.d(TAG, "Starting 5-second ambient data fetching");
            ambientDataFetcher.start();
        }
    }

    private void stopAmbientDataFetching() {
        if (ambientDataFetcher != null && ambientDataFetcher.isRunning()) {
            Log.d(TAG, "Stopping ambient data fetching");
            ambientDataFetcher.stop();
        }
    }

    private void forceAmbientDataFetch() {
        if (ambientDataFetcher != null && isFragmentActive) {
            ambientDataFetcher.fetchImmediately();
        }
    }

    // Safe UI update method
    private void safeUpdateUI(Runnable updateTask) {
        if (isAdded() && getActivity() != null && isFragmentActive) {
            if (Thread.currentThread() == getActivity().getMainLooper().getThread()) {
                // Already on UI thread
                updateTask.run();
            } else {
                // Post to UI thread
                getActivity().runOnUiThread(() -> {
                    if (isAdded() && isFragmentActive) {
                        updateTask.run();
                    }
                });
            }
        }
    }

    private void safeShowSnackbar(String message) {
        safeUpdateUI(() -> {
            if (rootView != null) {
                Snackbar.make(rootView, message, Snackbar.LENGTH_SHORT).show();
            }
        });
    }

    // UI Update Methods
    private void updateAllUI() {
        if (!isAdded() || !isFragmentActive) return;

        updateCurrentTime();
        updateBulbState();
        updateBrightnessControls();
        updateControlStates();
        updateBulbVisualization();
        updateBrightnessSummary();
        updateDimmingCard();
        updateTuyaStatusCard();
    }

    private void updateCurrentTime() {
        if (textTime == null || !isAdded()) return;

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        textTime.setText(getString(R.string.current_time_placeholder, currentTime));
    }

    private void updateBulbState() {
        if (!isAdded()) return;

        if (switchBulb != null) {
            switchBulb.setChecked(lightSettings.isBulbOn());
        }
        if (switchAutoMode != null) {
            switchAutoMode.setChecked(isAutoModeEnabled);
            switchAutoMode.setEnabled(isApiDataAvailable && lightSettings.isBulbOn() && isTuyaConnected);
        }
    }

    private void updateBrightnessControls() {
        if (!isAdded()) return;

        int currentBrightness = lightSettings.getBrightness();
        if (sliderBrightness != null) {
            sliderBrightness.setProgress(currentBrightness);
            sliderBrightness.setEnabled(lightSettings.isBulbOn() && isTuyaConnected);
        }
        updateBrightnessDisplay(currentBrightness);
    }

    private void updateBrightnessDisplay(int brightness) {
        if (textBrightness == null || !isAdded()) return;

        if (currentAmbientData != null && isApiDataAvailable) {
            int ambientPercentage = currentAmbientData.getPercentageAsInt();
            BrightnessDisplayHelper.updateBulbBrightnessDisplay(
                    requireContext(), textBrightness, brightness, ambientPercentage);
        } else {
            textBrightness.setText(getString(R.string.brightness_value, brightness));
        }
    }

    private void updateControlStates() {
        if (!isAdded()) return;

        boolean canControl = lightSettings.isBulbOn() && isTuyaConnected;
        boolean isDimmed = lightSettings.isDimmed();

        if (buttonDim != null) {
            buttonDim.setEnabled(canControl && !isDimmed);
        }
        if (buttonBrighten != null) {
            buttonBrighten.setEnabled(canControl && isDimmed);
        }
        if (buttonOptimize != null) {
            buttonOptimize.setEnabled(canControl && isApiDataAvailable);
            buttonOptimize.setText(isApiDataAvailable ? "Optimize" : "Fetch Data");
        }
    }

    private void updateBulbVisualization() {
        if (imageBulb == null || !isAdded()) return;

        try {
            int color = Color.parseColor(lightSettings.getColor());
            imageBulb.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));

            float alpha = lightSettings.isBulbOn() ? lightSettings.getBrightness() / 100f : 0.2f;
            imageBulb.setAlpha(alpha);
        } catch (Exception e) {
            Log.e(TAG, "Error updating bulb visualization", e);
        }
    }

    private void updateAmbientLightDisplay(AmbientLightApiService.AmbientLightData data) {
        if (!isAdded()) return;

        BrightnessDisplayHelper.updateAmbientLightDisplay(
                requireContext(),
                textAmbientLight,
                progressAmbientLight,
                data,
                lightSettings.getBrightness()
        );
    }

    private void updateBrightnessSummary() {
        if (!isAdded()) return;

        if (textBrightnessSummary != null && currentAmbientData != null && isApiDataAvailable) {
            String summary = BrightnessDisplayHelper.createBrightnessSummary(
                    currentAmbientData, lightSettings.getBrightness());
            textBrightnessSummary.setText(summary);

            if (cardBrightnessSummary != null) {
                cardBrightnessSummary.setVisibility(View.VISIBLE);
            }
        } else if (cardBrightnessSummary != null) {
            cardBrightnessSummary.setVisibility(View.GONE);
        }
    }

    private void updateDimmingCard() {
        if (cardDimming != null && isAdded()) {
            cardDimming.setVisibility(lightSettings.isDimmed() ? View.VISIBLE : View.GONE);
        }
    }

    private void updateTuyaStatus(String status) {
        if (textTuyaStatus != null && isAdded()) {
            textTuyaStatus.setText("Tuya Cloud: " + status);
        }
    }

    private void updateTuyaStatusCard() {
        if (!isAdded()) return;

        if (cardTuyaStatus != null && textTuyaStatus != null) {
            cardTuyaStatus.setVisibility(View.VISIBLE);

            String status = isTuyaConnected ?
                    "Connected - Cloud API Active" :
                    "Disconnected - Check credentials";
            updateTuyaStatus(status);
        }
    }

    private void notifySettingsChanged() {
        if (callback != null && isAdded()) {
            callback.accept(lightSettings);
        }
    }

    // Public methods
    public void setCallback(Consumer<LightSettings> callback) {
        this.callback = callback;
    }

    public void refreshData(LightSettings settings) {
        if (!isAdded()) return;

        this.lightSettings = settings;
        safeUpdateUI(this::updateAllUI);
    }

    // Fragment lifecycle
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "Fragment resumed - starting services");

        isFragmentActive = true;

        if (lightSettings != null && lightSettings.isBulbOn()) {
            startAmbientDataFetching();
        }

        // Reconnect to Tuya Cloud if needed
        if (tuyaCloudService != null && !tuyaCloudService.isConnected()) {
            Log.d(TAG, "Reconnecting to Tuya Cloud...");
            tuyaCloudService.connect();
        }

        safeUpdateUI(this::updateAllUI);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "Fragment paused - pausing services");

        if (ambientDataFetcher != null) {
            ambientDataFetcher.pause();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "Fragment stopped - stopping background tasks");

        // Stop ambient fetching when not visible
        stopAmbientDataFetching();

        // Stop time updater
        if (timeUpdater != null && !timeUpdater.isInterrupted()) {
            timeUpdater.interrupt();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "Fragment destroyed - cleaning up");

        // Mark fragment as inactive
        isFragmentActive = false;

        // Stop all services first
        if (ambientDataFetcher != null) {
            ambientDataFetcher.stop();
            ambientDataFetcher = null;
        }

        if (timeUpdater != null && !timeUpdater.isInterrupted()) {
            timeUpdater.interrupt();
            try {
                timeUpdater.join(1000); // Wait up to 1 second for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for time updater to stop");
            }
            timeUpdater = null;
        }

        if (tuyaCloudService != null) {
            try {
                tuyaCloudService.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting Tuya service", e);
            }
            tuyaCloudService = null;
        }

        // Clear references
        rootView = null;
        currentAmbientData = null;
        callback = null;
        lightSettings = null;

        // Clear UI references to prevent memory leaks
        textTime = null;
        textBrightness = null;
        textAmbientLight = null;
        textBrightnessSummary = null;
        textTuyaStatus = null;
        switchBulb = null;
        switchAutoMode = null;
        sliderBrightness = null;
        buttonDim = null;
        buttonBrighten = null;
        buttonCancelDimming = null;
        buttonOptimize = null;
        imageBulb = null;
        progressAmbientLight = null;
        cardDimming = null;
        cardBrightnessSummary = null;
        cardTuyaStatus = null;

        Log.d(TAG, "HomeFragment cleanup completed");
    }

    @Override
    public void onDetach() {
        super.onDetach();
        Log.d(TAG, "Fragment detached from activity");

        // Final safety measure - mark as inactive
        isFragmentActive = false;

        // Clear any remaining references
        callback = null;
    }

    // Helper method to safely get context
    private android.content.Context getSafeContext() {
        if (isAdded() && getContext() != null) {
            return getContext();
        }
        return null;
    }

    // Helper method to safely get string resources
    private String getSafeString(int resId, Object... formatArgs) {
        try {
            android.content.Context context = getSafeContext();
            if (context != null) {
                if (formatArgs.length > 0) {
                    return context.getString(resId, formatArgs);
                } else {
                    return context.getString(resId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting string resource", e);
        }
        return ""; // Return empty string as fallback
    }

    // Error handling for critical operations
    private void handleCriticalError(String operation, Exception e) {
        Log.e(TAG, "Critical error in " + operation, e);

        safeUpdateUI(() -> {
            // Show error state in UI if possible
            if (textTuyaStatus != null) {
                textTuyaStatus.setText("Error: " + operation + " failed");
            }
        });

        safeShowSnackbar("Error: " + operation + " failed. Please restart the app.");
    }

    // Method to check if fragment is in valid state for operations
    private boolean isValidState() {
        return isAdded() &&
                getActivity() != null &&
                !getActivity().isFinishing() &&
                isFragmentActive &&
                rootView != null;
    }

    // Enhanced error-safe brightness display update
    private void safeBrightnessDisplayUpdate(int brightness) {
        if (!isValidState()) return;

        try {
            if (currentAmbientData != null && isApiDataAvailable) {
                int ambientPercentage = currentAmbientData.getPercentageAsInt();

                android.content.Context context = getSafeContext();
                if (context != null && textBrightness != null) {
                    BrightnessDisplayHelper.updateBulbBrightnessDisplay(
                            context, textBrightness, brightness, ambientPercentage);
                }
            } else if (textBrightness != null) {
                String brightnessText = getSafeString(R.string.brightness_value, brightness);
                if (!brightnessText.isEmpty()) {
                    textBrightness.setText(brightnessText);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating brightness display", e);
            // Fallback to simple text
            if (textBrightness != null) {
                textBrightness.setText(brightness + "%");
            }
        }
    }

    // Enhanced error-safe ambient light display update
    private void safeAmbientLightDisplayUpdate(AmbientLightApiService.AmbientLightData data) {
        if (!isValidState() || data == null) return;

        try {
            android.content.Context context = getSafeContext();
            if (context != null) {
                BrightnessDisplayHelper.updateAmbientLightDisplay(
                        context,
                        textAmbientLight,
                        progressAmbientLight,
                        data,
                        lightSettings != null ? lightSettings.getBrightness() : 0
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating ambient light display", e);
            handleCriticalError("ambient light display update", e);
        }
    }

    // Method to force refresh all UI components
    public void forceRefreshUI() {
        if (!isValidState()) {
            Log.w(TAG, "Cannot force refresh - fragment not in valid state");
            return;
        }

        safeUpdateUI(() -> {
            try {
                updateAllUI();
                Log.d(TAG, "UI force refresh completed");
            } catch (Exception e) {
                Log.e(TAG, "Error during force refresh", e);
                handleCriticalError("UI refresh", e);
            }
        });
    }

    // Method to get current fragment status for debugging
    public String getFragmentStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Fragment Status:\n");
        status.append("- Added: ").append(isAdded()).append("\n");
        status.append("- Active: ").append(isFragmentActive).append("\n");
        status.append("- Activity null: ").append(getActivity() == null).append("\n");
        status.append("- Tuya connected: ").append(isTuyaConnected).append("\n");
        status.append("- API data available: ").append(isApiDataAvailable).append("\n");
        status.append("- Auto mode: ").append(isAutoModeEnabled).append("\n");

        if (lightSettings != null) {
            status.append("- Bulb on: ").append(lightSettings.isBulbOn()).append("\n");
            status.append("- Brightness: ").append(lightSettings.getBrightness()).append("%\n");
        } else {
            status.append("- Light settings: null\n");
        }

        return status.toString();
    }

    // Emergency cleanup method - can be called from MainActivity if needed
    public void emergencyCleanup() {
        Log.w(TAG, "Emergency cleanup initiated");

        isFragmentActive = false;

        // Stop all background operations immediately
        if (timeUpdater != null) {
            timeUpdater.interrupt();
            timeUpdater = null;
        }

        if (ambientDataFetcher != null) {
            try {
                ambientDataFetcher.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping ambient fetcher during emergency cleanup", e);
            }
            ambientDataFetcher = null;
        }

        if (tuyaCloudService != null) {
            try {
                tuyaCloudService.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting Tuya during emergency cleanup", e);
            }
            tuyaCloudService = null;
        }

        // Clear all references
        callback = null;
        currentAmbientData = null;
        lightSettings = null;

        Log.d(TAG, "Emergency cleanup completed");
    }

    // Safe method to update brightness with full error handling
    public void safeBrightnessUpdate(int newBrightness) {
        if (!isValidState()) {
            Log.w(TAG, "Cannot update brightness - fragment not in valid state");
            return;
        }

        if (newBrightness < 0 || newBrightness > 100) {
            Log.w(TAG, "Invalid brightness value: " + newBrightness);
            return;
        }

        try {
            setBrightness(newBrightness);
        } catch (Exception e) {
            Log.e(TAG, "Error updating brightness", e);
            handleCriticalError("brightness update", e);
        }
    }

    // Safe method to toggle bulb state
    public void safeBulbToggle() {
        if (!isValidState()) {
            Log.w(TAG, "Cannot toggle bulb - fragment not in valid state");
            return;
        }

        try {
            boolean newState = !lightSettings.isBulbOn();
            lightSettings.setBulbOn(newState);

            if (isTuyaConnected && tuyaCloudService != null) {
                tuyaCloudService.setLightState(newState);
            }

            if (newState) {
                startAmbientDataFetching();
            } else {
                stopAmbientDataFetching();
                setAutoModeEnabled(false);
            }

            notifySettingsChanged();
            safeUpdateUI(() -> updateAllUI());

        } catch (Exception e) {
            Log.e(TAG, "Error toggling bulb state", e);
            handleCriticalError("bulb toggle", e);
        }
    }

    // Method to check connectivity status
    public boolean isFullyConnected() {
        return isValidState() &&
                isTuyaConnected &&
                isApiDataAvailable &&
                lightSettings != null;
    }

    // Method to get connectivity status string
    public String getConnectivityStatus() {
        if (!isValidState()) {
            return "Fragment not active";
        }

        StringBuilder status = new StringBuilder();
        status.append("Tuya: ").append(isTuyaConnected ? "Connected" : "Disconnected").append(", ");
        status.append("Ambient API: ").append(isApiDataAvailable ? "Available" : "Unavailable").append(", ");
        status.append("Auto Mode: ").append(isAutoModeEnabled ? "Enabled" : "Disabled");

        return status.toString();
    }
}