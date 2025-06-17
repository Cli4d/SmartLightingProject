// HomeFragment.java (Updated with Persistent State Tracking)
package com.smart.smartbulb.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";
    private static final String PREFS_NAME = "SmartBulbPrefs";

    // Device configuration preferences
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String DEFAULT_DEVICE_ID = "bfc64cc8fa223bd6afxqtb";

    // Bulb state persistence preferences
    private static final String PREF_BULB_STATE = "bulb_on_state";
    private static final String PREF_BRIGHTNESS = "bulb_brightness";
    private static final String PREF_COLOR = "bulb_color";
    private static final String PREF_AUTO_MODE = "auto_mode_enabled";
    private static final String PREF_LAST_UPDATE_TIME = "last_state_update";

    // Default values
    private static final boolean DEFAULT_BULB_STATE = false;
    private static final int DEFAULT_BRIGHTNESS = 50;
    private static final String DEFAULT_COLOR = "#FFFFFF";
    private static final boolean DEFAULT_AUTO_MODE = false;

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
    private Button buttonConnectDevice;
    private FrameLayout imageBulb;
    private ProgressBar progressAmbientLight;
    private MaterialCardView cardDimming;
    private MaterialCardView cardBrightnessSummary;
    private MaterialCardView cardTuyaStatus;
    private MaterialCardView cardDeviceConfig;

    // Device configuration UI
    private TextInputLayout textInputLayoutDeviceId;
    private TextInputEditText editTextDeviceId;

    // State tracking
    private AmbientLightApiService.AmbientLightData currentAmbientData;
    private volatile boolean isApiDataAvailable = false;
    private volatile boolean isTuyaConnected = false;
    private volatile boolean isAutoModeEnabled = false;
    private volatile boolean isFragmentActive = false;
    private volatile boolean isInitializingFromSavedState = false;
    private String currentDeviceId = DEFAULT_DEVICE_ID;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_home, container, false);
        Log.d(TAG, "HomeFragment view created with persistent state tracking");
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "Initializing HomeFragment with persistent state management");

        isFragmentActive = true;

        // Initialize components in order
        loadAllStateFromPreferences();  // Load saved state first
        initializeLightSettings();      // Apply saved state to light settings
        initializeViews();
        setupUIEventHandlers();
        initializeTuyaCloudService();
        initializeAmbientDataFetcher();
        startTimeUpdater();

        // Apply loaded state to UI
        safeUpdateUI(() -> {
            updateAllUI();
            syncUIWithSavedState();
        });

        // Start ambient fetching if bulb was on when we left
        if (lightSettings.isBulbOn()) {
            Log.d(TAG, "Restoring ambient data fetching - bulb was on");
            startAmbientDataFetching();
        }
    }

    // ================================
    // PERSISTENT STATE MANAGEMENT
    // ================================

    private void loadAllStateFromPreferences() {
        if (getContext() == null) {
            Log.w(TAG, "Context not available for loading preferences");
            return;
        }

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Load device configuration
        currentDeviceId = prefs.getString(PREF_DEVICE_ID, DEFAULT_DEVICE_ID);

        // Load bulb state
        boolean savedBulbState = prefs.getBoolean(PREF_BULB_STATE, DEFAULT_BULB_STATE);
        int savedBrightness = prefs.getInt(PREF_BRIGHTNESS, DEFAULT_BRIGHTNESS);
        String savedColor = prefs.getString(PREF_COLOR, DEFAULT_COLOR);
        isAutoModeEnabled = prefs.getBoolean(PREF_AUTO_MODE, DEFAULT_AUTO_MODE);

        long lastUpdateTime = prefs.getLong(PREF_LAST_UPDATE_TIME, 0);
        long timeSinceLastUpdate = System.currentTimeMillis() - lastUpdateTime;

        Log.d(TAG, String.format("Loaded saved state - Bulb: %s, Brightness: %d%%, Color: %s, Auto: %s, Last update: %d mins ago",
                savedBulbState ? "ON" : "OFF",
                savedBrightness,
                savedColor,
                isAutoModeEnabled ? "ON" : "OFF",
                timeSinceLastUpdate / (1000 * 60)));

        // Store loaded values for initialization
        isInitializingFromSavedState = true;
    }

    private void saveAllStateToPreferences() {
        if (getContext() == null || lightSettings == null) {
            Log.w(TAG, "Cannot save state - context or settings not available");
            return;
        }

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // Save device configuration
        editor.putString(PREF_DEVICE_ID, currentDeviceId);

        // Save bulb state
        editor.putBoolean(PREF_BULB_STATE, lightSettings.isBulbOn());
        editor.putInt(PREF_BRIGHTNESS, lightSettings.getBrightness());
        editor.putString(PREF_COLOR, lightSettings.getColor());
        editor.putBoolean(PREF_AUTO_MODE, isAutoModeEnabled);
        editor.putLong(PREF_LAST_UPDATE_TIME, System.currentTimeMillis());

        editor.apply();

        Log.d(TAG, String.format("Saved state - Bulb: %s, Brightness: %d%%, Color: %s, Auto: %s",
                lightSettings.isBulbOn() ? "ON" : "OFF",
                lightSettings.getBrightness(),
                lightSettings.getColor(),
                isAutoModeEnabled ? "ON" : "OFF"));
    }

    private void saveBulbStateToPreferences(boolean isOn) {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(PREF_BULB_STATE, isOn)
                    .putLong(PREF_LAST_UPDATE_TIME, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "Saved bulb state: " + (isOn ? "ON" : "OFF"));
        }
    }

    private void saveBrightnessToPreferences(int brightness) {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putInt(PREF_BRIGHTNESS, brightness)
                    .putLong(PREF_LAST_UPDATE_TIME, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "Saved brightness: " + brightness + "%");
        }
    }

    private void saveAutoModeToPreferences(boolean enabled) {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putBoolean(PREF_AUTO_MODE, enabled)
                    .putLong(PREF_LAST_UPDATE_TIME, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "Saved auto mode: " + (enabled ? "ON" : "OFF"));
        }
    }

    private void saveColorToPreferences(String color) {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(PREF_COLOR, color)
                    .putLong(PREF_LAST_UPDATE_TIME, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "Saved color: " + color);
        }
    }

    private void syncUIWithSavedState() {
        if (!isAdded() || !isInitializingFromSavedState) return;

        Log.d(TAG, "Syncing UI with saved state");

        // Apply saved state to UI components without triggering listeners
        if (switchBulb != null) {
            switchBulb.setOnCheckedChangeListener(null);
            switchBulb.setChecked(lightSettings.isBulbOn());
            setupBulbSwitchListener(); // Re-attach listener
        }

        if (switchAutoMode != null) {
            switchAutoMode.setOnCheckedChangeListener(null);
            switchAutoMode.setChecked(isAutoModeEnabled);
            setupAutoModeSwitchListener(); // Re-attach listener
        }

        if (sliderBrightness != null) {
            sliderBrightness.setOnSeekBarChangeListener(null);
            sliderBrightness.setProgress(lightSettings.getBrightness());
            setupBrightnessSliderListener(); // Re-attach listener
        }

        isInitializingFromSavedState = false;
        Log.d(TAG, "UI sync with saved state completed");
    }

    // ================================
    // INITIALIZATION METHODS
    // ================================

    private void initializeLightSettings() {
        if (getArguments() != null) {
            lightSettings = (LightSettings) getArguments().getSerializable("lightSettings");
        }

        if (lightSettings == null) {
            lightSettings = new LightSettings();
        }

        // Apply saved state to light settings if we're initializing from saved state
        if (isInitializingFromSavedState && getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            boolean savedBulbState = prefs.getBoolean(PREF_BULB_STATE, DEFAULT_BULB_STATE);
            int savedBrightness = prefs.getInt(PREF_BRIGHTNESS, DEFAULT_BRIGHTNESS);
            String savedColor = prefs.getString(PREF_COLOR, DEFAULT_COLOR);

            lightSettings.setBulbOn(savedBulbState);
            lightSettings.setBrightness(savedBrightness);
            lightSettings.setColor(savedColor);

            Log.d(TAG, "Applied saved state to light settings - Bulb: " + savedBulbState +
                    ", Brightness: " + savedBrightness + "%, Color: " + savedColor);
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
        buttonConnectDevice = rootView.findViewById(R.id.buttonConnectDevice);

        imageBulb = rootView.findViewById(R.id.imageBulb);
        progressAmbientLight = rootView.findViewById(R.id.progressAmbientLight);

        cardDimming = rootView.findViewById(R.id.cardDimming);
        cardBrightnessSummary = rootView.findViewById(R.id.cardBrightnessSummary);
        cardTuyaStatus = rootView.findViewById(R.id.cardTuyaStatus);
        cardDeviceConfig = rootView.findViewById(R.id.cardDeviceConfig);

        // Device configuration components
        textInputLayoutDeviceId = rootView.findViewById(R.id.textInputLayoutDeviceId);
        editTextDeviceId = rootView.findViewById(R.id.editTextDeviceId);

        // Set initial device ID in the input field
        if (editTextDeviceId != null) {
            editTextDeviceId.setText(currentDeviceId);
        }
    }

    private void setupUIEventHandlers() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot setup UI handlers - fragment not attached");
            return;
        }

        // Device ID input handling
        setupDeviceIdInputHandlers();

        // Setup listeners with state persistence
        setupBulbSwitchListener();
        setupAutoModeSwitchListener();
        setupBrightnessSliderListener();

        // Control buttons
        setupControlButtons();
    }

    // ================================
    // UI EVENT LISTENER SETUP
    // ================================

    private void setupBulbSwitchListener() {
        if (switchBulb != null) {
            switchBulb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Skip if this change is from restoring saved state
                if (isInitializingFromSavedState) return;

                Log.d(TAG, "User toggled bulb switch: " + isChecked);

                // Update Tuya device via Cloud API
                if (isTuyaConnected && tuyaCloudService != null) {
                    tuyaCloudService.setLightState(isChecked);
                }

                // Update settings and save state
                lightSettings.setBulbOn(isChecked);
                saveBulbStateToPreferences(isChecked);

                // Manage ambient data fetching
                if (isChecked) {
                    startAmbientDataFetching();
                } else {
                    stopAmbientDataFetching();
                    setAutoModeEnabled(false);
                }

                notifySettingsChanged();
                safeUpdateUI(() -> updateAllUI());

                safeShowSnackbar("Bulb " + (isChecked ? "turned ON" : "turned OFF"));
            });
        }
    }

    private void setupAutoModeSwitchListener() {
        if (switchAutoMode != null) {
            switchAutoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                // Skip if this change is from restoring saved state
                if (isInitializingFromSavedState) return;

                Log.d(TAG, "User toggled auto mode: " + isChecked);
                setAutoModeEnabled(isChecked);

                if (isChecked && !isApiDataAvailable) {
                    safeShowSnackbar("Fetching ambient data to enable auto mode...");
                    forceAmbientDataFetch();
                }

                safeUpdateUI(() -> updateAllUI());
                safeShowSnackbar("Auto mode " + (isChecked ? "enabled" : "disabled"));
            });
        }
    }

    private void setupBrightnessSliderListener() {
        if (sliderBrightness != null) {
            sliderBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isAdded() && !isInitializingFromSavedState) {
                        updateBrightnessDisplay(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    if (isInitializingFromSavedState) return;

                    Log.d(TAG, "User started adjusting brightness - disabling auto mode");
                    setAutoModeEnabled(false);
                    if (switchAutoMode != null) {
                        switchAutoMode.setChecked(false);
                    }
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (!isAdded() || isInitializingFromSavedState) return;

                    int newBrightness = seekBar.getProgress();
                    Log.d(TAG, "User set brightness to: " + newBrightness + "%");

                    // Update Tuya device via Cloud API
                    if (isTuyaConnected && tuyaCloudService != null) {
                        tuyaCloudService.setBrightness(newBrightness);
                    }

                    // Update settings and save state
                    lightSettings.setBrightness(newBrightness);
                    saveBrightnessToPreferences(newBrightness);

                    notifySettingsChanged();
                    safeUpdateUI(() -> updateAllUI());

                    safeShowSnackbar("Brightness set to " + newBrightness + "%");
                }
            });
        }
    }

    private void setupDeviceIdInputHandlers() {
        // Connect button click handler
        if (buttonConnectDevice != null) {
            buttonConnectDevice.setOnClickListener(v -> {
                String newDeviceId = getDeviceIdFromInput();
                if (isValidDeviceId(newDeviceId)) {
                    connectToNewDevice(newDeviceId);
                } else {
                    safeShowSnackbar("Please enter a valid device ID");
                    if (textInputLayoutDeviceId != null) {
                        textInputLayoutDeviceId.setError("Invalid device ID format");
                    }
                }
            });
        }

        // Real-time input validation
        if (editTextDeviceId != null) {
            editTextDeviceId.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // Clear error when user types
                    if (textInputLayoutDeviceId != null) {
                        textInputLayoutDeviceId.setError(null);
                    }

                    // Update connect button state
                    updateConnectButtonState();
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private String getDeviceIdFromInput() {
        if (editTextDeviceId != null && editTextDeviceId.getText() != null) {
            return editTextDeviceId.getText().toString().trim();
        }
        return "";
    }

    private boolean isValidDeviceId(String deviceId) {
        // Basic validation - Tuya device IDs are typically 20-22 characters alphanumeric
        return deviceId != null &&
                deviceId.length() >= 20 &&
                deviceId.length() <= 25 &&
                deviceId.matches("[a-zA-Z0-9]+");
    }

    private void updateConnectButtonState() {
        if (buttonConnectDevice != null) {
            String deviceId = getDeviceIdFromInput();
            boolean isValid = isValidDeviceId(deviceId);
            boolean isDifferent = !deviceId.equals(currentDeviceId);

            buttonConnectDevice.setEnabled(isValid && isDifferent);
            buttonConnectDevice.setText(isDifferent ? "Connect" : "Connected");
        }
    }

    private void connectToNewDevice(String newDeviceId) {
        Log.d(TAG, "Connecting to new device: " + newDeviceId);

        // Disconnect current device
        if (tuyaCloudService != null) {
            tuyaCloudService.disconnect();
            isTuyaConnected = false;
        }

        // Update device ID and save
        currentDeviceId = newDeviceId;
        saveAllStateToPreferences(); // Save all state including new device ID

        // Show connecting status
        safeUpdateUI(() -> {
            updateTuyaStatus("Connecting to " + newDeviceId.substring(0, 8) + "...");
            updateConnectButtonState();
        });

        // Initialize new connection
        initializeTuyaCloudService();

        safeShowSnackbar("Connecting to device: " + newDeviceId.substring(0, 8) + "...");
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

    // ================================
    // TUYA CLOUD SERVICE
    // ================================

    private void initializeTuyaCloudService() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot initialize Tuya service - fragment not attached");
            return;
        }

        Log.d(TAG, "Initializing Cloud API service with device ID: " + currentDeviceId);

        tuyaCloudService = new TuyaCloudApiService();
        tuyaCloudService.setDeviceId(currentDeviceId);

        tuyaCloudService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                if (!isFragmentActive || !isAdded()) {
                    Log.w(TAG, "Fragment not active, skipping connection update");
                    return;
                }

                Log.d(TAG, "Cloud device connected: " + deviceName);
                isTuyaConnected = true;

                // Sync state between cloud and local settings
                syncWithTuyaCloud();

                safeUpdateUI(() -> {
                    updateTuyaStatus("Connected: " + deviceName);
                    updateConnectButtonState();
                    updateAllUI();
                });
                safeShowSnackbar("Connected to: " + deviceName);
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isFragmentActive) return;

                Log.w(TAG, "Cloud device disconnected");
                isTuyaConnected = false;
                setAutoModeEnabled(false);

                safeUpdateUI(() -> {
                    updateTuyaStatus("Disconnected");
                    updateConnectButtonState();
                    updateAllUI();
                });
                safeShowSnackbar("Device disconnected");
            }

            @Override
            public void onBrightnessChanged(int brightness) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Cloud brightness changed: " + brightness + "%");
                lightSettings.setBrightness(brightness);
                saveBrightnessToPreferences(brightness);

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

                Log.d(TAG, "Cloud light state changed: " + (isOn ? "ON" : "OFF"));
                lightSettings.setBulbOn(isOn);
                saveBulbStateToPreferences(isOn);

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

                Log.d(TAG, "Cloud color changed: " + hexColor);
                lightSettings.setColor(hexColor);
                saveColorToPreferences(hexColor);

                safeUpdateUI(() -> updateBulbVisualization());
            }

            @Override
            public void onError(String error) {
                if (!isFragmentActive) return;

                Log.e(TAG, "Cloud error: " + error);
                safeUpdateUI(() -> {
                    updateTuyaStatus("Error: " + error);
                    updateConnectButtonState();
                });
                safeShowSnackbar("Connection error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Cloud success: " + message);
            }
        });

        // Connect to Tuya Cloud
        tuyaCloudService.connect();
    }

    private void syncWithTuyaCloud() {
        if (tuyaCloudService != null && isAdded()) {
            // When connecting to Tuya Cloud, decide whether to push local state or pull cloud state
            // Priority: Use local saved state if it's newer, otherwise sync from cloud

            if (getContext() != null) {
                SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                long lastLocalUpdate = prefs.getLong(PREF_LAST_UPDATE_TIME, 0);
                long timeSinceUpdate = System.currentTimeMillis() - lastLocalUpdate;

                // If local state is recent (less than 5 minutes old), push to cloud
                if (timeSinceUpdate < 5 * 60 * 1000) {
                    Log.d(TAG, "Pushing recent local state to cloud");
                    pushLocalStateToCloud();
                } else {
                    Log.d(TAG, "Pulling current state from cloud");
                    pullStateFromCloud();
                }
            } else {
                // Fallback: pull from cloud
                pullStateFromCloud();
            }
        }
    }

    private void pushLocalStateToCloud() {
        if (tuyaCloudService != null && lightSettings != null) {
            Log.d(TAG, "Pushing local state to cloud - Bulb: " + lightSettings.isBulbOn() +
                    ", Brightness: " + lightSettings.getBrightness() + "%");

            tuyaCloudService.setLightState(lightSettings.isBulbOn());
            if (lightSettings.isBulbOn()) {
                tuyaCloudService.setBrightness(lightSettings.getBrightness());
            }
        }
    }

    private void pullStateFromCloud() {
        if (tuyaCloudService != null) {
            Log.d(TAG, "Pulling state from cloud");

            // Sync state from Tuya Cloud API to our settings
            boolean cloudBulbState = tuyaCloudService.isLightOn();
            int cloudBrightness = tuyaCloudService.getCurrentBrightness();
            String cloudColor = tuyaCloudService.getCurrentColor();

            lightSettings.setBulbOn(cloudBulbState);
            lightSettings.setBrightness(cloudBrightness);
            lightSettings.setColor(cloudColor);

            // Save the cloud state locally
            saveAllStateToPreferences();

            Log.d(TAG, "Synced from Cloud - Light: " + cloudBulbState +
                    ", Brightness: " + cloudBrightness + "%, Color: " + cloudColor);

            // Get latest device status from cloud
            tuyaCloudService.getDeviceStatus();
        }
    }

    // ================================
    // AMBIENT DATA AND AUTO ADJUSTMENT
    // ================================

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
            Log.d(TAG, String.format("Auto-adjusting Cloud: %d%% → %d%% (ambient: %d%%)",
                    currentBrightness, optimalBrightness, ambientPercentage));

            // Update Tuya device via Cloud API
            if (tuyaCloudService != null) {
                tuyaCloudService.setBrightness(optimalBrightness);
            }

            // Update local settings and save
            lightSettings.setBrightness(optimalBrightness);
            saveBrightnessToPreferences(optimalBrightness);

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

            Log.d(TAG, String.format("Optimizing Cloud brightness: %d%% (ambient: %d%%)",
                    optimalBrightness, ambientPercentage));

            setBrightness(optimalBrightness);

            String message = String.format(Locale.getDefault(),
                    "Optimized: %d%% + %d%% = 100%%",
                    optimalBrightness, ambientPercentage);
            safeShowSnackbar(message);
        } else {
            if (!isTuyaConnected) {
                safeShowSnackbar("Device not connected - check device ID");
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

        // Update local settings and save
        lightSettings.setBrightness(brightness);
        saveBrightnessToPreferences(brightness);

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
        saveAutoModeToPreferences(enabled);
        Log.d(TAG, "Auto mode " + (enabled ? "enabled" : "disabled"));

        if (enabled && isApiDataAvailable && currentAmbientData != null) {
            // Immediate optimization when enabling auto mode
            autoAdjustTuyaBrightness(currentAmbientData);
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

    // ================================
    // UI SAFE UPDATE METHODS
    // ================================

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

    // ================================
    // UI UPDATE METHODS
    // ================================

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
        updateDeviceConfigCard();
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
            textTuyaStatus.setText("Cloud: " + status);
        }
    }

    private void updateTuyaStatusCard() {
        if (!isAdded()) return;

        if (cardTuyaStatus != null && textTuyaStatus != null) {
            cardTuyaStatus.setVisibility(View.VISIBLE);

            String status = isTuyaConnected ?
                    "Connected - Device ID: " + currentDeviceId.substring(0, 8) + "..." :
                    "Disconnected - Check device ID";
            updateTuyaStatus(status);
        }
    }

    private void updateDeviceConfigCard() {
        if (!isAdded()) return;

        if (cardDeviceConfig != null) {
            cardDeviceConfig.setVisibility(View.VISIBLE);
        }

        updateConnectButtonState();
    }

    private void notifySettingsChanged() {
        if (callback != null && isAdded()) {
            callback.accept(lightSettings);
        }
    }

    // ================================
    // PUBLIC METHODS
    // ================================

    public void setCallback(Consumer<LightSettings> callback) {
        this.callback = callback;
    }

    public void refreshData(LightSettings settings) {
        if (!isAdded()) return;

        this.lightSettings = settings;
        saveAllStateToPreferences(); // Save the new settings
        safeUpdateUI(this::updateAllUI);
    }

    // Method to programmatically set device ID (for external use)
    public void setDeviceId(String deviceId) {
        if (isValidDeviceId(deviceId)) {
            if (editTextDeviceId != null) {
                editTextDeviceId.setText(deviceId);
            }
            connectToNewDevice(deviceId);
        }
    }

    // Method to get current device ID
    public String getCurrentDeviceId() {
        return currentDeviceId;
    }

    // Method to get current saved state info
    public String getSavedStateInfo() {
        if (getContext() == null) return "Context not available";

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        StringBuilder info = new StringBuilder();
        info.append("Saved State Info:\n");
        info.append("Device ID: ").append(prefs.getString(PREF_DEVICE_ID, "Not set")).append("\n");
        info.append("Bulb State: ").append(prefs.getBoolean(PREF_BULB_STATE, false) ? "ON" : "OFF").append("\n");
        info.append("Brightness: ").append(prefs.getInt(PREF_BRIGHTNESS, 0)).append("%\n");
        info.append("Color: ").append(prefs.getString(PREF_COLOR, "Not set")).append("\n");
        info.append("Auto Mode: ").append(prefs.getBoolean(PREF_AUTO_MODE, false) ? "ON" : "OFF").append("\n");

        long lastUpdate = prefs.getLong(PREF_LAST_UPDATE_TIME, 0);
        if (lastUpdate > 0) {
            long minutesAgo = (System.currentTimeMillis() - lastUpdate) / (1000 * 60);
            info.append("Last Update: ").append(minutesAgo).append(" minutes ago\n");
        } else {
            info.append("Last Update: Never\n");
        }

        return info.toString();
    }

    // Method to clear saved state (for debugging/reset)
    public void clearSavedState() {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "Cleared all saved state");
            safeShowSnackbar("Saved state cleared - restart to see changes");
        }
    }

    // Method to force save current state
    public void forceSaveState() {
        saveAllStateToPreferences();
        safeShowSnackbar("State saved");
        Log.d(TAG, "Forced save of current state");
    }

    // ================================
    // FRAGMENT LIFECYCLE
    // ================================

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "Fragment resumed - restoring state and starting services");

        isFragmentActive = true;

        // Restore ambient data fetching if bulb was on
        if (lightSettings != null && lightSettings.isBulbOn()) {
            Log.d(TAG, "Resuming ambient data fetching - bulb is on");
            startAmbientDataFetching();
        }

        // Reconnect to Tuya Cloud if needed
        if (tuyaCloudService != null && !tuyaCloudService.isConnected()) {
            Log.d(TAG, "Reconnecting to Cloud...");
            tuyaCloudService.connect();
        }

        safeUpdateUI(this::updateAllUI);
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "Fragment paused - saving state and pausing services");

        // Save current state before pausing
        saveAllStateToPreferences();

        if (ambientDataFetcher != null) {
            ambientDataFetcher.pause();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "Fragment stopped - saving state and stopping background tasks");

        // Save state before stopping
        saveAllStateToPreferences();

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
        Log.d(TAG, "Fragment destroyed - saving final state and cleaning up");

        // Final state save before destruction
        saveAllStateToPreferences();

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
                Log.e(TAG, "Error disconnecting service", e);
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
        buttonConnectDevice = null;
        imageBulb = null;
        progressAmbientLight = null;
        cardDimming = null;
        cardBrightnessSummary = null;
        cardTuyaStatus = null;
        cardDeviceConfig = null;
        textInputLayoutDeviceId = null;
        editTextDeviceId = null;

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

    // ================================
    // UTILITY AND HELPER METHODS
    // ================================

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

        safeShowSnackbar("Error: " + operation + " failed. Check device ID and try again.");
    }

    // Method to check if fragment is in valid state for operations
    private boolean isValidState() {
        return isAdded() &&
                getActivity() != null &&
                !getActivity().isFinishing() &&
                isFragmentActive &&
                rootView != null;
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
        status.append("- connected: ").append(isTuyaConnected).append("\n");
        status.append("- API data available: ").append(isApiDataAvailable).append("\n");
        status.append("- Auto mode: ").append(isAutoModeEnabled).append("\n");
        status.append("- Current device ID: ").append(currentDeviceId).append("\n");
        status.append("- Initializing from saved: ").append(isInitializingFromSavedState).append("\n");

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

        // Save state before emergency cleanup
        try {
            saveAllStateToPreferences();
        } catch (Exception e) {
            Log.e(TAG, "Error saving state during emergency cleanup", e);
        }

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
                Log.e(TAG, "Error disconnecting during emergency cleanup", e);
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
            saveBulbStateToPreferences(newState);

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
        status.append("Cloud: ").append(isTuyaConnected ? "Connected" : "Disconnected").append(", ");
        status.append("Device ID: ").append(currentDeviceId.substring(0, 8)).append("..., ");
        status.append("Ambient API: ").append(isApiDataAvailable ? "Available" : "Unavailable").append(", ");
        status.append("Auto Mode: ").append(isAutoModeEnabled ? "Enabled" : "Disabled");

        return status.toString();
    }

    // Device ID management utility methods
    public boolean isDeviceIdConfigured() {
        return currentDeviceId != null && !currentDeviceId.equals(DEFAULT_DEVICE_ID);
    }

    public void resetToDefaultDeviceId() {
        if (editTextDeviceId != null) {
            editTextDeviceId.setText(DEFAULT_DEVICE_ID);
        }
        connectToNewDevice(DEFAULT_DEVICE_ID);
    }

    // Method to validate and update device ID from external sources
    public boolean updateDeviceIdIfValid(String newDeviceId) {
        if (isValidDeviceId(newDeviceId) && !newDeviceId.equals(currentDeviceId)) {
            setDeviceId(newDeviceId);
            return true;
        }
        return false;
    }

    // Method to get device connection info for debugging
    public String getDeviceConnectionInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Device Connection Info:\n");
        info.append("Current Device ID: ").append(currentDeviceId).append("\n");
        info.append("Connection Status: ").append(isTuyaConnected ? "Connected" : "Disconnected").append("\n");
        info.append("Service Initialized: ").append(tuyaCloudService != null).append("\n");

        if (tuyaCloudService != null) {
            info.append("Service Connected: ").append(tuyaCloudService.isConnected()).append("\n");
            info.append("Current Brightness: ").append(tuyaCloudService.getCurrentBrightness()).append("%\n");
            info.append("Light State: ").append(tuyaCloudService.isLightOn() ? "ON" : "OFF").append("\n");
            info.append("Current Color: ").append(tuyaCloudService.getCurrentColor()).append("\n");
        }

        return info.toString();
    }
}