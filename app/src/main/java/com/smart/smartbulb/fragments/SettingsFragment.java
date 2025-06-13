package com.smart.smartbulb.fragments;

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

import java.util.function.Consumer;


public class SettingsFragment extends Fragment {
    private static final String TAG = "SettingsFragment";

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

    // New Tuya integration
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

    // State tracking
    private LightSettings lightSettings;
    private Consumer<LightSettings> callback;
    private volatile boolean isTuyaConnected = false;
    private volatile boolean isFragmentActive = false;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_settings, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "SettingsFragment initialized with Tuya integration");

        isFragmentActive = true;

        // Find views
        initializeViews();

        // Get light settings from arguments
        initializeLightSettings();

        // Initialize Tuya service
        initializeTuyaService();

        // Set up UI
        setupUI();
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

        Log.d(TAG, "Initializing Tuya Cloud API service for SettingsFragment");

        tuyaService = new TuyaCloudApiService();
        tuyaService.setDeviceId("bfc64cc8fa223bd6afxqtb"); // TODO: Make this configurable

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                if (!isFragmentActive || !isAdded()) return;

                Log.d(TAG, "Tuya connected in Settings: " + deviceName);
                isTuyaConnected = true;

                safeUpdateUI(() -> {
                    updateTuyaConnectionStatus("Connected: " + deviceName, true);
                    updateTuyaControls();
                });

                safeShowSnackbar("Tuya device connected: " + deviceName);

                // Sync current state
                tuyaService.getDeviceStatus();
            }

            @Override
            public void onDeviceDisconnected() {
                if (!isFragmentActive) return;

                Log.w(TAG, "Tuya disconnected in Settings");
                isTuyaConnected = false;

                safeUpdateUI(() -> {
                    updateTuyaConnectionStatus("Disconnected", false);
                    updateTuyaControls();
                });

                safeShowSnackbar("Tuya device disconnected");
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
                safeUpdateUI(() -> updateTuyaConnectionStatus("Error: " + error, false));
                safeShowSnackbar("Tuya error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Settings Tuya success: " + message);
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
    }

    private void setupScheduleSettings() {
        if (!isAdded()) return;

        // Set initial state
        if (switchAutoSchedule != null) {
            switchAutoSchedule.setChecked(lightSettings.isAutoScheduleEnabled());
        }
        updateScheduleVisibility();
        updateScheduleDisplay();

        // Auto schedule toggle
        if (switchAutoSchedule != null) {
            switchAutoSchedule.setOnCheckedChangeListener((buttonView, isChecked) -> {
                lightSettings.setAutoScheduleEnabled(isChecked);
                updateScheduleVisibility();
                notifySettingsChanged();

                String message = isChecked ?
                        "Auto schedule enabled - lights will adjust at sunset and bedtime" :
                        "Auto schedule disabled - manual control only";
                safeShowSnackbar(message);
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
                    }

                    lightSettings.setBrightness(newBrightness);
                    notifySettingsChanged();
                }
            });
        }

        // Reconnect button
        if (buttonReconnectTuya != null) {
            buttonReconnectTuya.setOnClickListener(v -> {
                Log.d(TAG, "Manual Tuya reconnect requested");
                if (tuyaService != null) {
                    tuyaService.connect();
                    safeShowSnackbar("Reconnecting to Tuya Cloud...");
                }
            });
        }

        updateTuyaControls();
    }

    private void setupAdvancedControls() {
        if (!isAdded()) return;

        // Test sunset button
        if (buttonTestSunset != null) {
            buttonTestSunset.setOnClickListener(v -> {
                Log.d(TAG, "Test sunset triggered");
                if (isTuyaConnected && tuyaService != null) {
                    tuyaService.setBrightness(80);
                    lightSettings.setBrightness(80);
                    lightSettings.setDimmed(false);
                    notifySettingsChanged();
                    safeShowSnackbar("🌅 Sunset mode activated (80% brightness)");
                } else {
                    safeShowSnackbar("Tuya device not connected");
                }
            });
        }

        // Test bedtime button
        if (buttonTestBedtime != null) {
            buttonTestBedtime.setOnClickListener(v -> {
                Log.d(TAG, "Test bedtime triggered");
                if (isTuyaConnected && tuyaService != null) {
                    tuyaService.setBrightness(20);
                    lightSettings.setBrightness(20);
                    lightSettings.setDimmed(true);
                    notifySettingsChanged();
                    safeShowSnackbar("🌙 Bedtime mode activated (20% brightness)");
                } else {
                    safeShowSnackbar("Tuya device not connected");
                }
            });
        }

        // Reset schedule button
        if (buttonResetSchedule != null) {
            buttonResetSchedule.setOnClickListener(v -> {
                Log.d(TAG, "Reset schedule triggered");
                lightSettings.setSunsetTime("18:30");
                lightSettings.setBedtime("22:00");
                lightSettings.setAutoScheduleEnabled(false);

                updateScheduleDisplay();
                if (switchAutoSchedule != null) {
                    switchAutoSchedule.setChecked(false);
                }
                updateScheduleVisibility();

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
            buttonTestSunset.setEnabled(isTuyaConnected);
        }

        if (buttonTestBedtime != null) {
            buttonTestBedtime.setEnabled(isTuyaConnected);
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

    public void refreshSettings(LightSettings settings) {
        if (!isAdded() || settings == null) return;

        this.lightSettings = settings;
        safeUpdateUI(() -> {
            updateScheduleDisplay();
            updateTuyaControls();
            updateColorSelection();

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

    // Test methods for debugging
    public void testSunsetMode() {
        if (buttonTestSunset != null) {
            buttonTestSunset.performClick();
        }
    }

    public void testBedtimeMode() {
        if (buttonTestBedtime != null) {
            buttonTestBedtime.performClick();
        }
    }

    public void forceTuyaReconnect() {
        if (buttonReconnectTuya != null) {
            buttonReconnectTuya.performClick();
        }
    }

    // Fragment lifecycle
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "SettingsFragment resumed");

        isFragmentActive = true;

        // Reconnect Tuya if needed
        if (tuyaService != null && !tuyaService.isConnected()) {
            Log.d(TAG, "Reconnecting Tuya service...");
            tuyaService.connect();
        }

        // Update UI
        safeUpdateUI(() -> {
            updateTuyaControls();
            updateScheduleDisplay();
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "SettingsFragment destroyed - cleaning up");

        // Mark fragment as inactive
        isFragmentActive = false;

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
}