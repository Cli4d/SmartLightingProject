package com.smart.smartbulb.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.smart.smartbulb.R;
import com.smart.smartbulb.models.LightSettings;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;
import com.smart.smartbulb.services.TuyaCloudApiService;
import com.smart.smartbulb.services.ScheduleManager; // NEW IMPORT

import java.util.function.Consumer;

public class SettingsFragment extends Fragment {
    private static final String TAG = "SettingsFragment";
    private static final String PREFS_NAME = "SmartBulbPrefs";
    private static final String PREF_DEVICE_ID = "device_id";
    private static final String DEFAULT_DEVICE_ID = "bfc64cc8fa223bd6afxqtb";

    private View rootView;

    // Schedule controls
    private SwitchMaterial switchAutoSchedule;
    private LinearLayout scheduleSettingsContainer;
    private MaterialCardView sunsetLayout;
    private MaterialCardView bedtimeLayout;
    private TextView textSunset;
    private TextView textBedtime;
    private TextView textSunsetBrightness;
    private TextView textBedtimeBrightness;

    // Color controls
    private LinearLayout colorContainer;
    private View colorPreview;

    // Profile controls
    private EditText editUsername;
    private Button buttonSaveUsername;

    // Tuya integration
    private TuyaCloudApiService tuyaService;
    private MaterialCardView cardTuyaConnection;
    private TextView textTuyaConnectionStatus;
    private Button buttonReconnectTuya;
    private SwitchMaterial switchBulbOnOff;
    private SeekBar seekBarBrightness;
    private TextView textCurrentBrightness;

    // Advanced controls
    private Button buttonTestSunset;
    private Button buttonTestBedtime;
    private Button buttonResetSchedule;

    // NEW: Schedule manager for automatic scheduling
    private ScheduleManager scheduleManager;
    private TextView textScheduleStatus; // NEW: Status display

    // State tracking
    private LightSettings lightSettings;
    private Consumer<LightSettings> callback;
    private volatile boolean isTuyaConnected = false;
    private volatile boolean isFragmentActive = false;
    private String currentDeviceId = DEFAULT_DEVICE_ID;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_settings, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "SettingsFragment initialized with automatic scheduling support");

        isFragmentActive = true;

        // Initialize components
        loadDeviceIdFromPreferences();
        initializeViews();
        initializeLightSettings();
        initializeScheduleManager(); // NEW: Initialize schedule manager
        initializeTuyaService();
        setupUI();
    }

    private void loadDeviceIdFromPreferences() {
        if (getContext() != null) {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            currentDeviceId = prefs.getString(PREF_DEVICE_ID, DEFAULT_DEVICE_ID);
            Log.d(TAG, "SettingsFragment loaded device ID from preferences: " + currentDeviceId);
        }
    }

    // NEW: Initialize the schedule manager
    private void initializeScheduleManager() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot initialize schedule manager - fragment not attached");
            return;
        }

        Log.d(TAG, "Initializing ScheduleManager");
        scheduleManager = new ScheduleManager(requireContext());

        // Restore any existing schedules from preferences
        scheduleManager.restoreSchedulingFromPreferences();
    }

    private void initializeViews() {
        if (!isAdded() || rootView == null) {
            Log.w(TAG, "Cannot initialize views - fragment not attached");
            return;
        }

        // Schedule controls
        switchAutoSchedule = rootView.findViewById(R.id.switchAutoSchedule);
        scheduleSettingsContainer = rootView.findViewById(R.id.scheduleSettingsContainer);
        sunsetLayout = rootView.findViewById(R.id.sunsetLayout);
        bedtimeLayout = rootView.findViewById(R.id.bedtimeLayout);
        textSunset = rootView.findViewById(R.id.textSunset);
        textBedtime = rootView.findViewById(R.id.textBedtime);
        textSunsetBrightness = rootView.findViewById(R.id.textSunsetBrightness);
        textBedtimeBrightness = rootView.findViewById(R.id.textBedtimeBrightness);
        textScheduleStatus = rootView.findViewById(R.id.textScheduleStatus); // NEW: Status display

        // Color controls
        colorContainer = rootView.findViewById(R.id.colorContainer);
        colorPreview = rootView.findViewById(R.id.colorPreview);

        // Profile controls
        editUsername = rootView.findViewById(R.id.editUsername);
        buttonSaveUsername = rootView.findViewById(R.id.buttonSaveUsername);

        // Tuya controls
        cardTuyaConnection = rootView.findViewById(R.id.cardTuyaConnection);
        textTuyaConnectionStatus = rootView.findViewById(R.id.textTuyaConnectionStatus);
        buttonReconnectTuya = rootView.findViewById(R.id.buttonReconnectTuya);
        switchBulbOnOff = rootView.findViewById(R.id.switchBulbOnOff);
        seekBarBrightness = rootView.findViewById(R.id.seekBarBrightness);
        textCurrentBrightness = rootView.findViewById(R.id.textCurrentBrightness);

        // Advanced controls
        buttonTestSunset = rootView.findViewById(R.id.buttonTestSunset);
        buttonTestBedtime = rootView.findViewById(R.id.buttonTestBedtime);
        buttonResetSchedule = rootView.findViewById(R.id.buttonResetSchedule);
    }

    private void initializeLightSettings() {
        if (getArguments() != null) {
            lightSettings = (LightSettings) getArguments().getSerializable("lightSettings");
        }

        if (lightSettings == null) {
            lightSettings = new LightSettings();
            // Set default sunset and bedtime if not set
            if (lightSettings.getSunsetTime() == null) {
                lightSettings.setSunsetTime("18:30");
            }
            if (lightSettings.getBedtime() == null) {
                lightSettings.setBedtime("22:00");
            }
        }
    }

    private void initializeTuyaService() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot initialize Tuya service - fragment not attached");
            return;
        }

        Log.d(TAG, "Initializing Tuya Cloud API service for SettingsFragment with device ID: " + currentDeviceId);

        tuyaService = new TuyaCloudApiService();

        // Set device ID from preferences (configurable)
        if (currentDeviceId != null && !currentDeviceId.trim().isEmpty()) {
            tuyaService.setDeviceId(currentDeviceId);
        } else {
            Log.w(TAG, "No device ID configured in SettingsFragment");
            safeUpdateUI(() -> updateTuyaConnectionStatus("Device ID not configured", false));
            return;
        }

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Tuya connected in Settings: " + deviceName);
                isTuyaConnected = true;

                safeUpdateUI(() -> {
                    updateTuyaConnectionStatus("Connected: " + deviceName + " (" + currentDeviceId.substring(0, 8) + "...)", true);
                    updateTuyaControls();
                });

                safeShowSnackbar("Device connected: " + deviceName);

                // Sync current state
                tuyaService.getDeviceStatus();
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isFragmentActive) return;

                Log.w(TAG, "Tuya disconnected in Settings");
                isTuyaConnected = false;

                safeUpdateUI(() -> {
                    updateTuyaConnectionStatus("Disconnected - Device: " + currentDeviceId.substring(0, 8) + "...", false);
                    updateTuyaControls();
                });

                safeShowSnackbar("Device disconnected");
            }

            @Override
            public void onBrightnessChanged(int brightness) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Settings Tuya brightness changed: " + brightness + "%");
                lightSettings.setBrightness(brightness);

                safeUpdateUI(() -> {
                    if (seekBarBrightness != null) {
                        seekBarBrightness.setProgress(brightness);
                    }
                    updateBrightnessDisplay(brightness);
                });

                notifySettingsChanged();
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Settings Tuya light state changed: " + (isOn ? "ON" : "OFF"));
                lightSettings.setBulbOn(isOn);

                safeUpdateUI(() -> {
                    if (switchBulbOnOff != null) {
                        switchBulbOnOff.setChecked(isOn);
                    }
                    updateTuyaControls();
                });

                notifySettingsChanged();
            }

            @Override
            public void onColorChanged(String hexColor) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Settings Tuya color changed: " + hexColor);
                lightSettings.setColor(hexColor);

                safeUpdateUI(() -> {
                    if (colorPreview != null) {
                        colorPreview.setBackgroundColor(Color.parseColor(hexColor));
                    }
                    updateColorSelection();
                });

                notifySettingsChanged();
            }

            @Override
            public void onError(String error) {
                if (!isFragmentActive) return;

                Log.e(TAG, "Settings Tuya error: " + error);
                safeUpdateUI(() -> updateTuyaConnectionStatus("Error: " + error + " (Device: " + currentDeviceId.substring(0, 8) + "...)", false));
                safeShowSnackbar("Cloud error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Settings Cloud success: " + message);
            }
        });

        // Connect to Tuya Cloud
        tuyaService.connect();
    }

    public void setCallback(Consumer<LightSettings> callback) {
        this.callback = callback;
    }

    private void setupUI() {
        setupScheduleSettings();
        setupColorPicker();
        setupProfileSettings();
        setupTuyaControls();
        setupAdvancedControls();
        updateScheduleStatusDisplay(); // NEW: Update schedule status
    }

    // MODIFIED: Enhanced schedule settings with automatic scheduling
    private void setupScheduleSettings() {
        if (!isAdded()) return;

        // Set initial state
        if (switchAutoSchedule != null) {
            switchAutoSchedule.setChecked(lightSettings.isAutoScheduleEnabled());
        }
        updateScheduleVisibility();
        updateScheduleDisplay();

        // MODIFIED: Auto schedule toggle with actual scheduling
        if (switchAutoSchedule != null) {
            switchAutoSchedule.setOnCheckedChangeListener((buttonView, isChecked) -> {
                lightSettings.setAutoScheduleEnabled(isChecked);
                updateScheduleVisibility();
                notifySettingsChanged();

                // CRITICAL FIX: Add actual scheduling logic
                if (isChecked) {
                    // Enable automatic scheduling
                    if (scheduleManager != null) {
                        scheduleManager.scheduleAutoModes(lightSettings);
                        Log.d(TAG, "Automatic scheduling enabled");
                    }
                    String message = "Auto schedule enabled - lights will adjust at sunset and bedtime";
                    safeShowSnackbar(message);
                } else {
                    // Disable automatic scheduling
                    if (scheduleManager != null) {
                        scheduleManager.cancelScheduledAlarms();
                        Log.d(TAG, "Automatic scheduling disabled");
                    }
                    String message = "Auto schedule disabled - manual control only";
                    safeShowSnackbar(message);
                }

                updateScheduleStatusDisplay(); // NEW: Update status display
            });
        }

        // Sunset time picker
        if (sunsetLayout != null) {
            sunsetLayout.setOnClickListener(v -> showTimePicker("sunset"));
        }

        // Bedtime time picker
        if (bedtimeLayout != null) {
            bedtimeLayout.setOnClickListener(v -> showTimePicker("bedtime"));
        }
    }

    private void setupTuyaControls() {
        if (!isAdded()) return;

        // Bulb on/off switch
        if (switchBulbOnOff != null) {
            switchBulbOnOff.setChecked(lightSettings.isBulbOn());
            switchBulbOnOff.setOnCheckedChangeListener((buttonView, isChecked) -> {
                Log.d(TAG, "Settings bulb switch toggled: " + isChecked);

                if (isTuyaConnected && tuyaService != null) {
                    tuyaService.setLightState(isChecked);
                } else if (!isTuyaConnected) {
                    safeShowSnackbar("Device not connected - check device ID configuration");
                }

                lightSettings.setBulbOn(isChecked);
                updateTuyaControls();
                notifySettingsChanged();
            });
        }

        // Brightness slider
        if (seekBarBrightness != null) {
            seekBarBrightness.setProgress(lightSettings.getBrightness());
            seekBarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && isAdded()) {
                        updateBrightnessDisplay(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    // Optional: Show feedback
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (!isAdded()) return;

                    int newBrightness = seekBar.getProgress();
                    Log.d(TAG, "Settings brightness changed to: " + newBrightness + "%");

                    if (isTuyaConnected && tuyaService != null) {
                        tuyaService.setBrightness(newBrightness);
                    } else if (!isTuyaConnected) {
                        safeShowSnackbar("Device not connected - check device ID configuration");
                    }

                    lightSettings.setBrightness(newBrightness);
                    notifySettingsChanged();
                }
            });
        }

        // Reconnect button
        if (buttonReconnectTuya != null) {
            buttonReconnectTuya.setOnClickListener(v -> {
                Log.d(TAG, "Manual Cloud reconnect requested for device: " + currentDeviceId);

                if (!tuyaService.isDeviceIdSet()) {
                    // Reload device ID from preferences in case it was updated
                    loadDeviceIdFromPreferences();
                    if (currentDeviceId != null && !currentDeviceId.trim().isEmpty()) {
                        tuyaService.setDeviceId(currentDeviceId);
                    } else {
                        safeShowSnackbar("Device ID not configured. Please set device ID in Home tab.");
                        return;
                    }
                }

                if (tuyaService != null) {
                    tuyaService.connect();
                    safeShowSnackbar("Reconnecting to Cloud with device: " + currentDeviceId.substring(0, 8) + "...");
                }
            });
        }

        updateTuyaControls();
    }

    // MODIFIED: Enhanced advanced controls with schedule manager integration
    private void setupAdvancedControls() {
        if (!isAdded()) return;

        // MODIFIED: Test sunset button with schedule manager
        if (buttonTestSunset != null) {
            buttonTestSunset.setOnClickListener(v -> {
                Log.d(TAG, "Test sunset triggered via schedule manager");

                if (scheduleManager != null) {
                    // Use schedule manager for consistent behavior
                    scheduleManager.testSunsetMode();
                    safeShowSnackbar("🌅 Test sunset mode activated via schedule manager");
                } else if (isTuyaConnected && tuyaService != null) {
                    // Fallback to direct Tuya control
                    tuyaService.setBrightness(80);
                    lightSettings.setBrightness(80);
                    lightSettings.setDimmed(false);
                    notifySettingsChanged();
                    safeShowSnackbar("🌅 Sunset mode activated (80% brightness)");
                } else {
                    safeShowSnackbar("Cloud device not connected - check device ID configuration");
                }
            });
        }

        // MODIFIED: Test bedtime button with schedule manager
        if (buttonTestBedtime != null) {
            buttonTestBedtime.setOnClickListener(v -> {
                Log.d(TAG, "Test bedtime triggered via schedule manager");

                if (scheduleManager != null) {
                    // Use schedule manager for consistent behavior
                    scheduleManager.testBedtimeMode();
                    safeShowSnackbar("🌙 Test bedtime mode activated via schedule manager");
                } else if (isTuyaConnected && tuyaService != null) {
                    // Fallback to direct Tuya control
                    tuyaService.setBrightness(20);
                    lightSettings.setBrightness(20);
                    lightSettings.setDimmed(true);
                    notifySettingsChanged();
                    safeShowSnackbar("🌙 Bedtime mode activated (20% brightness)");
                } else {
                    safeShowSnackbar("Cloud device not connected - check device ID configuration");
                }
            });
        }

        // MODIFIED: Reset schedule button with schedule manager integration
        if (buttonResetSchedule != null) {
            buttonResetSchedule.setOnClickListener(v -> {
                Log.d(TAG, "Reset schedule triggered");

                // Cancel any existing schedules
                if (scheduleManager != null) {
                    scheduleManager.cancelScheduledAlarms();
                }

                // Reset to defaults
                lightSettings.setSunsetTime("18:30");
                lightSettings.setBedtime("22:00");
                lightSettings.setAutoScheduleEnabled(false);

                updateScheduleDisplay();
                if (switchAutoSchedule != null) {
                    switchAutoSchedule.setChecked(false);
                }
                updateScheduleVisibility();
                updateScheduleStatusDisplay(); // NEW: Update status

                notifySettingsChanged();
                safeShowSnackbar("Schedule reset to defaults");
            });
        }
    }

    private void setupColorPicker() {
        if (colorContainer == null || !isAdded()) return;

        // Set initial color for all color options
        for (int i = 0; i < colorContainer.getChildCount(); i++) {
            View colorView = colorContainer.getChildAt(i);
            if (colorView.getTag() != null) {
                String colorHex = (String) colorView.getTag();
                colorView.setBackgroundColor(Color.parseColor(colorHex));

                // Check if this is the current color
                if (colorHex.equalsIgnoreCase(lightSettings.getColor())) {
                    colorView.setSelected(true);
                }

                // Set click listeners
                colorView.setOnClickListener(v -> {
                    Log.d(TAG, "Color selected: " + colorHex);

                    // Deselect all other colors
                    for (int j = 0; j < colorContainer.getChildCount(); j++) {
                        colorContainer.getChildAt(j).setSelected(false);
                    }

                    // Select this color
                    v.setSelected(true);

                    // Update Tuya device
                    if (isTuyaConnected && tuyaService != null) {
                        tuyaService.setColor(colorHex);
                    } else if (!isTuyaConnected) {
                        safeShowSnackbar("Device not connected - check device ID configuration");
                    }

                    // Update settings
                    lightSettings.setColor(colorHex);
                    if (colorPreview != null) {
                        colorPreview.setBackgroundColor(Color.parseColor(colorHex));
                    }
                    notifySettingsChanged();

                    safeShowSnackbar("Light color updated");
                });
            }
        }

        // Set initial preview color
        if (colorPreview != null) {
            colorPreview.setBackgroundColor(Color.parseColor(lightSettings.getColor()));
        }
    }

    private void setupProfileSettings() {
        if (!isAdded()) return;

        // Username
        if (editUsername != null) {
            editUsername.setText(lightSettings.getUsername());
        }

        if (buttonSaveUsername != null) {
            buttonSaveUsername.setOnClickListener(v -> {
                if (editUsername == null) return;

                String newUsername = editUsername.getText().toString().trim();
                if (!newUsername.isEmpty()) {
                    lightSettings.setUsername(newUsername);
                    notifySettingsChanged();
                    safeShowSnackbar("Username updated to " + newUsername);
                } else {
                    safeShowSnackbar("Please enter a valid username");
                }
            });
        }
    }

    private void updateScheduleVisibility() {
        if (scheduleSettingsContainer != null && switchAutoSchedule != null) {
            scheduleSettingsContainer.setVisibility(
                    switchAutoSchedule.isChecked() ? View.VISIBLE : View.GONE
            );
        }
    }

    private void updateScheduleDisplay() {
        if (textSunset != null) {
            textSunset.setText(lightSettings.getSunsetTime());
        }
        if (textBedtime != null) {
            textBedtime.setText(lightSettings.getBedtime());
        }
        if (textSunsetBrightness != null) {
            textSunsetBrightness.setText("80%"); // Fixed sunset brightness
        }
        if (textBedtimeBrightness != null) {
            textBedtimeBrightness.setText("20%"); // Fixed bedtime brightness
        }
    }

    // NEW: Update schedule status display
    private void updateScheduleStatusDisplay() {
        if (textScheduleStatus != null && scheduleManager != null && isAdded()) {
            if (lightSettings.isAutoScheduleEnabled()) {
                String status = scheduleManager.getSchedulingStatus();
                textScheduleStatus.setText("✅ Active: Next sunset " + lightSettings.getSunsetTime() + ", bedtime " + lightSettings.getBedtime());
                textScheduleStatus.setVisibility(View.VISIBLE);
            } else {
                textScheduleStatus.setText("❌ Automatic scheduling disabled");
                textScheduleStatus.setVisibility(View.VISIBLE);
            }
        }
    }

    private void updateTuyaConnectionStatus(String status, boolean isConnected) {
        if (textTuyaConnectionStatus != null && isAdded()) {
            textTuyaConnectionStatus.setText("Status: " + status);

            if (cardTuyaConnection != null) {
                // Change card color based on connection status
                int colorResId = isConnected ? R.color.success_green : R.color.error_red;
                cardTuyaConnection.setCardBackgroundColor(
                        ContextCompat.getColor(requireContext(), colorResId));
            }
        }
    }

    private void updateTuyaControls() {
        if (!isAdded()) return;

        boolean canControl = isTuyaConnected && lightSettings.isBulbOn();

        if (seekBarBrightness != null) {
            seekBarBrightness.setEnabled(canControl);
        }

        if (buttonTestSunset != null) {
            buttonTestSunset.setEnabled(true); // Always enabled (uses schedule manager)
        }

        if (buttonTestBedtime != null) {
            buttonTestBedtime.setEnabled(true); // Always enabled (uses schedule manager)
        }

        if (buttonReconnectTuya != null) {
            buttonReconnectTuya.setEnabled(!isTuyaConnected);
        }

        updateBrightnessDisplay(lightSettings.getBrightness());
    }

    private void updateBrightnessDisplay(int brightness) {
        if (textCurrentBrightness != null && isAdded()) {
            textCurrentBrightness.setText(brightness + "%");
        }
    }

    private void updateColorSelection() {
        if (colorContainer == null || !isAdded()) return;

        String currentColor = lightSettings.getColor();

        // Update selection based on current color
        for (int i = 0; i < colorContainer.getChildCount(); i++) {
            View colorView = colorContainer.getChildAt(i);
            if (colorView.getTag() != null) {
                String colorHex = (String) colorView.getTag();
                colorView.setSelected(colorHex.equalsIgnoreCase(currentColor));
            }
        }
    }

    // MODIFIED: Enhanced time picker with automatic rescheduling
    private void showTimePicker(String timeType) {
        if (!isAdded()) return;

        String currentTime;
        String title;

        if ("sunset".equals(timeType)) {
            currentTime = lightSettings.getSunsetTime();
            title = "Set Sunset Time";
        } else {
            currentTime = lightSettings.getBedtime();
            title = "Set Bedtime";
        }

        String[] timeParts = currentTime.split(":");
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);

        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(hour)
                .setMinute(minute)
                .setTitleText(title)
                .build();

        picker.addOnPositiveButtonClickListener(v -> {
            String newTime = String.format("%02d:%02d", picker.getHour(), picker.getMinute());

            if ("sunset".equals(timeType)) {
                lightSettings.setSunsetTime(newTime);
                String message = "Sunset time set to " + newTime + ". Lights will dim to 80% automatically.";
                safeShowSnackbar(message);
            } else {
                lightSettings.setBedtime(newTime);
                String message = "Bedtime set to " + newTime + ". Lights will dim to 20% automatically.";
                safeShowSnackbar(message);
            }

            updateScheduleDisplay();
            notifySettingsChanged();

            // CRITICAL FIX: Reschedule when times change
            if (lightSettings.isAutoScheduleEnabled() && scheduleManager != null) {
                Log.d(TAG, "Rescheduling due to time change: " + timeType + " -> " + newTime);
                scheduleManager.scheduleAutoModes(lightSettings);
            }

            updateScheduleStatusDisplay(); // NEW: Update status display
        });

        picker.show(getChildFragmentManager(), "TIME_PICKER");
    }

    // Safe UI update method
    private void safeUpdateUI(Runnable updateTask) {
        if (isAdded() && getActivity() != null && isFragmentActive) {
            if (Thread.currentThread() == getActivity().getMainLooper().getThread()) {
                updateTask.run();
            } else {
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

    private void notifySettingsChanged() {
        if (callback != null && isAdded()) {
            callback.accept(lightSettings);
        }
    }

    // Method to update device ID when changed from Home fragment
    public void updateDeviceId(String newDeviceId) {
        if (newDeviceId != null && !newDeviceId.equals(currentDeviceId)) {
            Log.d(TAG, "Updating device ID in Settings: " + newDeviceId);
            currentDeviceId = newDeviceId;

            // Disconnect current service and reinitialize with new device ID
            if (tuyaService != null) {
                tuyaService.disconnect();
                isTuyaConnected = false;
            }

            // Reinitialize with new device ID
            initializeTuyaService();

            safeUpdateUI(() -> updateTuyaControls());
        }
    }

    // Method to get current device ID
    public String getCurrentDeviceId() {
        return currentDeviceId;
    }

    // Public methods for external control
    public boolean isAutoScheduleEnabled() {
        return lightSettings != null && lightSettings.isAutoScheduleEnabled();
    }

    public String getSunsetTime() {
        return lightSettings != null ? lightSettings.getSunsetTime() : "18:30";
    }

    public String getBedtime() {
        return lightSettings != null ? lightSettings.getBedtime() : "22:00";
    }

    public int getSunsetBrightness() {
        return 80; // Fixed sunset brightness
    }

    public int getBedtimeBrightness() {
        return 20; // Fixed bedtime brightness
    }

    public boolean isTuyaConnected() {
        return isTuyaConnected;
    }

    // MODIFIED: Enhanced refresh with schedule status update
    public void refreshSettings(LightSettings settings) {
        if (!isAdded() || settings == null) return;

        this.lightSettings = settings;
        safeUpdateUI(() -> {
            updateScheduleDisplay();
            updateTuyaControls();
            updateColorSelection();
            updateScheduleStatusDisplay(); // NEW: Update schedule status

            if (switchAutoSchedule != null) {
                switchAutoSchedule.setChecked(settings.isAutoScheduleEnabled());
            }
            if (switchBulbOnOff != null) {
                switchBulbOnOff.setChecked(settings.isBulbOn());
            }
            if (seekBarBrightness != null) {
                seekBarBrightness.setProgress(settings.getBrightness());
            }
            if (editUsername != null) {
                editUsername.setText(settings.getUsername());
            }

            updateScheduleVisibility();
        });
    }

    // Test methods for debugging - MODIFIED to use schedule manager
    public void testSunsetMode() {
        if (scheduleManager != null) {
            scheduleManager.testSunsetMode();
        } else if (buttonTestSunset != null) {
            buttonTestSunset.performClick();
        }
    }

    public void testBedtimeMode() {
        if (scheduleManager != null) {
            scheduleManager.testBedtimeMode();
        } else if (buttonTestBedtime != null) {
            buttonTestBedtime.performClick();
        }
    }

    public void forceTuyaReconnect() {
        if (buttonReconnectTuya != null) {
            buttonReconnectTuya.performClick();
        }
    }

    // Method to reload device ID and reconnect (useful when device ID changes)
    public void reloadDeviceIdAndReconnect() {
        Log.d(TAG, "Reloading device ID and reconnecting...");

        // Reload device ID from preferences
        loadDeviceIdFromPreferences();

        // Update Tuya service with new device ID if changed
        if (tuyaService != null) {
            String currentServiceDeviceId = tuyaService.getDeviceId();
            if (!currentDeviceId.equals(currentServiceDeviceId)) {
                Log.d(TAG, "Device ID changed, updating service: " + currentServiceDeviceId + " -> " + currentDeviceId);
                updateDeviceId(currentDeviceId);
            } else {
                // Just reconnect with existing device ID
                forceTuyaReconnect();
            }
        }
    }

    // NEW: Get schedule manager for external access
    public ScheduleManager getScheduleManager() {
        return scheduleManager;
    }

    // NEW: Force schedule refresh
    public void forceScheduleRefresh() {
        if (scheduleManager != null && lightSettings != null) {
            Log.d(TAG, "Forcing schedule refresh");
            scheduleManager.forceReschedule();
            updateScheduleStatusDisplay();
        }
    }

    // Fragment lifecycle
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "SettingsFragment resumed");

        isFragmentActive = true;

        // Reload device ID in case it was changed in another fragment
        String previousDeviceId = currentDeviceId;
        loadDeviceIdFromPreferences();

        if (!currentDeviceId.equals(previousDeviceId)) {
            Log.d(TAG, "Device ID changed while fragment was paused, updating: " + previousDeviceId + " -> " + currentDeviceId);
            updateDeviceId(currentDeviceId);
        } else {
            // Reconnect Tuya if needed with existing device ID
            if (tuyaService != null && !tuyaService.isConnected()) {
                Log.d(TAG, "Reconnecting Tuya service...");
                tuyaService.connect();
            }
        }

        // Update UI
        safeUpdateUI(() -> {
            updateTuyaControls();
            updateScheduleDisplay();
            updateScheduleStatusDisplay(); // NEW: Update schedule status
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "SettingsFragment paused");
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "SettingsFragment stopped");
    }

    // MODIFIED: Enhanced cleanup with schedule manager
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "SettingsFragment destroyed - cleaning up");

        // Mark fragment as inactive
        isFragmentActive = false;

        // Clean up schedule manager (but don't cancel active schedules)
        if (scheduleManager != null) {
            // Save current state before cleanup
            if (lightSettings != null && lightSettings.isAutoScheduleEnabled()) {
                Log.d(TAG, "Preserving active schedules during fragment cleanup");
                // Schedules remain active in the background
            }
            scheduleManager = null;
        }

        // Disconnect Tuya service
        if (tuyaService != null) {
            try {
                tuyaService.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error disconnecting Tuya service", e);
            }
            tuyaService = null;
        }

        // Clear references
        rootView = null;
        callback = null;
        lightSettings = null;

        // Clear UI references
        switchAutoSchedule = null;
        scheduleSettingsContainer = null;
        sunsetLayout = null;
        bedtimeLayout = null;
        textSunset = null;
        textBedtime = null;
        textSunsetBrightness = null;
        textBedtimeBrightness = null;
        textScheduleStatus = null; // NEW: Clear schedule status
        colorContainer = null;
        colorPreview = null;
        editUsername = null;
        buttonSaveUsername = null;
        cardTuyaConnection = null;
        textTuyaConnectionStatus = null;
        buttonReconnectTuya = null;
        switchBulbOnOff = null;
        seekBarBrightness = null;
        textCurrentBrightness = null;
        buttonTestSunset = null;
        buttonTestBedtime = null;
        buttonResetSchedule = null;

        Log.d(TAG, "SettingsFragment cleanup completed");
    }

    @Override
    public void onDetach() {
        super.onDetach();
        Log.d(TAG, "SettingsFragment detached from activity");

        // Final safety measure
        isFragmentActive = false;
        callback = null;
    }

    // Emergency cleanup method - MODIFIED
    public void emergencyCleanup() {
        Log.w(TAG, "Emergency cleanup initiated for SettingsFragment");

        isFragmentActive = false;

        // Stop Tuya service immediately
        if (tuyaService != null) {
            try {
                tuyaService.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error during emergency Tuya disconnect", e);
            }
            tuyaService = null;
        }

        // Clean up schedule manager
        if (scheduleManager != null) {
            try {
                // Don't cancel schedules during emergency cleanup - they should continue running
                Log.d(TAG, "Preserving schedules during emergency cleanup");
            } catch (Exception e) {
                Log.e(TAG, "Error during emergency schedule manager cleanup", e);
            }
            scheduleManager = null;
        }

        // Clear all references
        callback = null;
        lightSettings = null;

        Log.d(TAG, "SettingsFragment emergency cleanup completed");
    }

    // Method to get fragment status for debugging - MODIFIED
    public String getFragmentStatus() {
        StringBuilder status = new StringBuilder();
        status.append("SettingsFragment Status:\n");
        status.append("- Added: ").append(isAdded()).append("\n");
        status.append("- Active: ").append(isFragmentActive).append("\n");
        status.append("- Activity null: ").append(getActivity() == null).append("\n");
        status.append("- Cloud connected: ").append(isTuyaConnected).append("\n");
        status.append("- Current device ID: ").append(currentDeviceId).append("\n");
        status.append("- Auto schedule enabled: ").append(isAutoScheduleEnabled()).append("\n");

        if (scheduleManager != null) {
            status.append("- Schedule manager active: true\n");
            status.append(scheduleManager.getSchedulingStatus());
        } else {
            status.append("- Schedule manager active: false\n");
        }

        if (lightSettings != null) {
            status.append("- Bulb on: ").append(lightSettings.isBulbOn()).append("\n");
            status.append("- Brightness: ").append(lightSettings.getBrightness()).append("%\n");
            status.append("- Color: ").append(lightSettings.getColor()).append("\n");
            status.append("- Username: ").append(lightSettings.getUsername()).append("\n");
            status.append("- Sunset time: ").append(lightSettings.getSunsetTime()).append("\n");
            status.append("- Bedtime: ").append(lightSettings.getBedtime()).append("\n");
        } else {
            status.append("- Light settings: null\n");
        }

        if (tuyaService != null) {
            status.append("- Service device ID: ").append(tuyaService.getDeviceId()).append("\n");
            status.append("- Service connected: ").append(tuyaService.isConnected()).append("\n");
        } else {
            status.append("- Cloud service: null\n");
        }

        return status.toString();
    }

    // Method to check if settings are properly configured - MODIFIED
    public boolean areSettingsValid() {
        return lightSettings != null &&
                currentDeviceId != null &&
                !currentDeviceId.trim().isEmpty() &&
                lightSettings.getSunsetTime() != null &&
                lightSettings.getBedtime() != null &&
                scheduleManager != null;
    }

    // Method to get connection status for external monitoring
    public String getConnectionStatus() {
        if (tuyaService == null) {
            return "Service not initialized";
        }

        StringBuilder status = new StringBuilder();
        status.append("Device: ").append(currentDeviceId.substring(0, 8)).append("..., ");
        status.append("Connected: ").append(isTuyaConnected ? "Yes" : "No").append(", ");
        status.append("Service ID Set: ").append(tuyaService.isDeviceIdSet() ? "Yes" : "No").append(", ");
        status.append("Scheduling: ").append(scheduleManager != null ? "Active" : "Inactive");

        return status.toString();
    }

    // Method to validate and set device ID (for external calls)
    public boolean setDeviceIdIfValid(String deviceId) {
        if (deviceId != null && deviceId.length() >= 20 && deviceId.length() <= 25 && deviceId.matches("[a-zA-Z0-9]+")) {
            updateDeviceId(deviceId);
            return true;
        }
        return false;
    }

    // Method to force settings refresh from external source - MODIFIED
    public void forceSettingsRefresh() {
        if (!isAdded()) {
            Log.w(TAG, "Cannot force refresh - fragment not attached");
            return;
        }

        Log.d(TAG, "Force refreshing SettingsFragment");

        // Reload device ID
        loadDeviceIdFromPreferences();

        // Refresh schedule manager
        if (scheduleManager != null) {
            scheduleManager.restoreSchedulingFromPreferences();
        }

        // Update UI
        safeUpdateUI(() -> {
            updateScheduleDisplay();
            updateTuyaControls();
            updateColorSelection();
            updateScheduleStatusDisplay(); // NEW: Update schedule status

            if (lightSettings != null) {
                if (switchAutoSchedule != null) {
                    switchAutoSchedule.setChecked(lightSettings.isAutoScheduleEnabled());
                }
                if (switchBulbOnOff != null) {
                    switchBulbOnOff.setChecked(lightSettings.isBulbOn());
                }
                if (seekBarBrightness != null) {
                    seekBarBrightness.setProgress(lightSettings.getBrightness());
                }
                if (editUsername != null) {
                    editUsername.setText(lightSettings.getUsername());
                }

                updateScheduleVisibility();
            }
        });

        Log.d(TAG, "SettingsFragment force refresh completed");
    }

    // Method to safely execute schedule commands - MODIFIED
    public void executeSunsetMode() {
        if (!areSettingsValid()) {
            Log.w(TAG, "Cannot execute sunset mode - settings not valid");
            return;
        }

        try {
            if (scheduleManager != null) {
                scheduleManager.testSunsetMode();
            } else {
                testSunsetMode();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing sunset mode", e);
            safeShowSnackbar("Error activating sunset mode");
        }
    }

    public void executeBedtimeMode() {
        if (!areSettingsValid()) {
            Log.w(TAG, "Cannot execute bedtime mode - settings not valid");
            return;
        }

        try {
            if (scheduleManager != null) {
                scheduleManager.testBedtimeMode();
            } else {
                testBedtimeMode();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing bedtime mode", e);
            safeShowSnackbar("Error activating bedtime mode");
        }
    }

    // Method to sync settings with external changes - MODIFIED
    public void syncWithExternalChanges(LightSettings externalSettings) {
        if (!isAdded() || externalSettings == null) return;

        Log.d(TAG, "Syncing SettingsFragment with external changes");

        // Update internal settings
        this.lightSettings = externalSettings;

        // Update UI to reflect changes
        refreshSettings(externalSettings);

        // Sync schedule manager if auto-schedule settings changed
        if (scheduleManager != null) {
            scheduleManager.scheduleAutoModes(externalSettings);
        }

        // If Tuya service state differs from external settings, sync it
        if (isTuyaConnected && tuyaService != null) {
            if (tuyaService.isLightOn() != externalSettings.isBulbOn()) {
                Log.d(TAG, "Syncing bulb state: " + externalSettings.isBulbOn());
                tuyaService.setLightState(externalSettings.isBulbOn());
            }

            if (tuyaService.getCurrentBrightness() != externalSettings.getBrightness()) {
                Log.d(TAG, "Syncing brightness: " + externalSettings.getBrightness() + "%");
                tuyaService.setBrightness(externalSettings.getBrightness());
            }

            if (!tuyaService.getCurrentColor().equals(externalSettings.getColor())) {
                Log.d(TAG, "Syncing color: " + externalSettings.getColor());
                tuyaService.setColor(externalSettings.getColor());
            }
        }
    }
}