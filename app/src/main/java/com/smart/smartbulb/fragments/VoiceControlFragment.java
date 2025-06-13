package com.smart.smartbulb.fragments;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.smart.smartbulb.R;
import com.smart.smartbulb.models.LightSettings;
import com.smart.smartbulb.services.EagleSpeakerIdentificationService;
import com.smart.smartbulb.services.EagleSpeakerIdentificationService.SpeakerProfile;
import com.smart.smartbulb.services.TuyaCloudApiService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Voice Control Fragment with PicoVoice Eagle Speaker Identification
 * Identifies speakers and applies their personalized lighting preferences
 */
public class VoiceControlFragment extends Fragment {
    private static final String TAG = "VoiceControlFragment";
    private static final int PERMISSION_REQUEST_AUDIO = 1001;

    // UI Components
    private View rootView;
    private MaterialCardView cardVoiceControl;
    private MaterialCardView cardCurrentSpeaker;
    private MaterialCardView cardEmptyState;
    private Button buttonIdentify;
    private Button buttonEnrollNew;
    private ProgressBar progressRecording;
    private TextView textRecordingStatus;
    private TextView textCurrentSpeaker;
    private TextView textSpeakerPreferences;
    private TextView textEnrolledTitle;
    private RecyclerView recyclerSpeakers;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabVoiceCommand;
    private View recordingStatusContainer;

    // Services
    private EagleSpeakerIdentificationService speakerService;
    private TuyaCloudApiService tuyaService;

    // State
    private LightSettings currentLightSettings;
    private SpeakerProfile currentSpeaker;
    private Consumer<LightSettings> lightSettingsCallback;
    private volatile boolean isRecording = false;
    private volatile boolean isProcessingApiCall = false;

    // Speaker adapter for RecyclerView
    private SpeakerAdapter speakerAdapter;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_voice_control, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "VoiceControlFragment onViewCreated with Eagle integration");

        // Initialize components
        initializeViews();
        initializeServices();
        setupUI();

        // Check audio permission and trigger initial UI update
        checkAudioPermission();
    }

    private void initializeViews() {
        cardVoiceControl = rootView.findViewById(R.id.cardVoiceControl);
        cardCurrentSpeaker = rootView.findViewById(R.id.cardCurrentSpeaker);
        cardEmptyState = rootView.findViewById(R.id.cardEmptyState);
        buttonIdentify = rootView.findViewById(R.id.buttonIdentify);
        buttonEnrollNew = rootView.findViewById(R.id.buttonEnrollNew);
        progressRecording = rootView.findViewById(R.id.progressRecording);
        textRecordingStatus = rootView.findViewById(R.id.textRecordingStatus);
        textCurrentSpeaker = rootView.findViewById(R.id.textCurrentSpeaker);
        textSpeakerPreferences = rootView.findViewById(R.id.textSpeakerPreferences);
        textEnrolledTitle = rootView.findViewById(R.id.textEnrolledTitle);
        recyclerSpeakers = rootView.findViewById(R.id.recyclerSpeakers);
        fabVoiceCommand = rootView.findViewById(R.id.fabVoiceCommand);
        recordingStatusContainer = rootView.findViewById(R.id.recordingStatusContainer);
    }

    private void initializeServices() {
        // Initialize Eagle speaker service
        speakerService = new EagleSpeakerIdentificationService(requireContext());

        // Set initialization callback to properly update UI state
        speakerService.setInitializationCallback(new EagleSpeakerIdentificationService.InitializationCallback() {
            @Override
            public void onInitializationComplete(boolean success) {
                Log.d(TAG, "Eagle initialization complete. Success: " + success);

                safeUpdateUI(() -> {
                    if (success) {
                        Log.d(TAG, "Eagle initialization successful");
                        Log.d(TAG, speakerService.getStorageInfo());
                    } else {
                        String status = speakerService.getInitializationStatus();
                        Log.e(TAG, "Eagle initialization failed: " + status);
                        safeShowSnackbar("Voice control issue: " + status);
                    }

                    // Always update UI state and refresh speaker list
                    updateControlStates();
                    refreshSpeakerList();
                });
            }
        });

        // Initialize Tuya service
        tuyaService = new TuyaCloudApiService();
        // IMPORTANT: Replace with your actual Tuya Device ID
        tuyaService.setDeviceId("bfc64cc8fa223bd6afxqtb");

        tuyaService.setCallback(new TuyaCloudApiService.TuyaCloudCallback() {
            @Override
            public void onDeviceConnected(String deviceName) {
                Log.d(TAG, "Tuya connected for voice control");
                safeUpdateUI(VoiceControlFragment.this::updateControlStates);
                safeShowSnackbar("Tuya device '" + deviceName + "' connected.");
            }

            @Override
            public void onDeviceDisconnected() {
                Log.w(TAG, "Tuya disconnected");
                safeUpdateUI(VoiceControlFragment.this::updateControlStates);
                safeShowSnackbar("Tuya device disconnected.");
            }

            @Override
            public void onBrightnessChanged(int brightness) {
                if (currentLightSettings != null) {
                    currentLightSettings.setBrightness(brightness);
                }
            }

            @Override
            public void onLightStateChanged(boolean isOn) {
                if (currentLightSettings != null) {
                    currentLightSettings.setBulbOn(isOn);
                }
            }

            @Override
            public void onColorChanged(String hexColor) {
                if (currentLightSettings != null) {
                    currentLightSettings.setColor(hexColor);
                }
            }

            @Override
            public void onError(String error) {
                safeShowSnackbar("Tuya error: " + error);
            }

            @Override
            public void onSuccess(String message) {
                Log.d(TAG, "Tuya success: " + message);
            }
        });

        // Connect to Tuya on startup
        tuyaService.connect();

        // Get current light settings from arguments
        if (getArguments() != null) {
            currentLightSettings = (LightSettings) getArguments().getSerializable("lightSettings");
        }
        if (currentLightSettings == null) {
            currentLightSettings = new LightSettings();
            Log.w(TAG, "currentLightSettings was null, initialized with default values.");
        }
    }

    private void setupUI() {
        // Identify button
        buttonIdentify.setOnClickListener(v -> identifySpeaker());

        // Enroll new speaker button
        buttonEnrollNew.setOnClickListener(v -> showEnrollmentDialog());

        // Add long-press to clear all profiles (for debugging/reset)
        buttonEnrollNew.setOnLongClickListener(v -> {
            showClearProfilesDialog();
            return true;
        });

        // Voice command FAB (placeholder)
        fabVoiceCommand.setOnClickListener(v -> processVoiceCommand());

        // Setup speaker list
        setupSpeakerList();

        // Initial UI update
        updateUI();
    }

    private void setupSpeakerList() {
        List<SpeakerProfile> speakers = speakerService.getEnrolledSpeakers();
        speakerAdapter = new SpeakerAdapter(speakers, new SpeakerAdapter.SpeakerActionListener() {
            @Override
            public void onSpeakerSelected(SpeakerProfile profile) {
                // Apply preferences manually
                applySpeakerPreferences(profile);
                safeShowSnackbar("Applied " + profile.getUserName() + "'s preferences.");
            }

            @Override
            public void onEditPreferences(SpeakerProfile profile) {
                showPreferencesDialog(profile);
            }

            @Override
            public void onDeleteSpeaker(SpeakerProfile profile) {
                confirmDeleteSpeaker(profile);
            }

            @Override
            public void onEnrollMore(SpeakerProfile profile) {
                // Continue enrollment for incomplete profiles
                enrollSpeaker(profile.getProfileId(), profile.getUserName(), profile.getRemainingEnrollmentsSpeechLength());
            }
        });

        recyclerSpeakers.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerSpeakers.setAdapter(speakerAdapter);
    }

    // --- Permissions Handling ---
    private void checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(requireActivity(),
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST_AUDIO);
        } else {
            updateControlStates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                safeShowSnackbar("Audio recording permission granted!");
                updateControlStates();
            } else {
                safeShowSnackbar("Audio permission is required for voice control features.");
                buttonIdentify.setEnabled(false);
                buttonEnrollNew.setEnabled(false);
                fabVoiceCommand.setEnabled(false);
            }
        }
    }

    /**
     * Initiates speaker identification process using Eagle
     */
    private void identifySpeaker() {
        if (isRecording || isProcessingApiCall) {
            safeShowSnackbar("Another operation is already in progress.");
            return;
        }

        // Check if Eagle is properly initialized
        if (speakerService == null || !speakerService.isInitialized()) {
            String status = speakerService != null ? speakerService.getInitializationStatus() : "Service not available";
            safeShowSnackbar("Voice service not ready: " + status);
            return;
        }

        // Check if there are any enrolled profiles
        boolean hasEnrolledProfiles = speakerService.getEnrolledSpeakers().stream()
                .anyMatch(p -> "Enrolled".equalsIgnoreCase(p.getEnrollmentStatus()));

        if (!hasEnrolledProfiles) {
            safeShowSnackbar("No fully enrolled speakers to identify. Please enroll a profile first.");
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            safeShowSnackbar("Audio permission not granted. Please grant it in settings.");
            checkAudioPermission();
            return;
        }

        isRecording = true;
        isProcessingApiCall = true;
        updateRecordingUI(true, "Listening... Speak for a few seconds for identification.");
        updateControlStates(); // ADDED: Update control states separately

        speakerService.identifySpeaker(new EagleSpeakerIdentificationService.SpeakerIdentificationCallback() {
            @Override
            public void onSpeakerIdentified(SpeakerProfile profile) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states
                    currentSpeaker = profile;
                    updateCurrentSpeakerUI();
                    applySpeakerPreferences(profile);
                    safeShowSnackbar("Welcome, " + profile.getUserName() + "!");
                });
            }

            @Override
            public void onIdentificationFailed(String error) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states
                    safeShowSnackbar("Identification failed: " + error);
                    currentSpeaker = null;
                    updateCurrentSpeakerUI();
                });
            }

            @Override
            public void onError(String error) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states
                    safeShowSnackbar("Error during identification: " + error);
                    currentSpeaker = null;
                    updateCurrentSpeakerUI();
                });
            }
        });
    }

    /**
     * Applies the speaker's lighting preferences to the Tuya device and updates UI.
     */
    private void applySpeakerPreferences(@NonNull SpeakerProfile profile) {
        if (profile == null) {
            Log.e(TAG, "Attempted to apply preferences for a null profile.");
            return;
        }
        if (!tuyaService.isConnected()) {
            safeShowSnackbar("Tuya device not connected. Cannot apply preferences.");
            Log.w(TAG, "Tuya not connected, skipping preference application.");
            return;
        }

        Log.d(TAG, "Applying preferences for " + profile.getUserName());

        // Apply brightness
        tuyaService.setBrightness(profile.getPreferredBrightness());
        if (currentLightSettings != null) {
            currentLightSettings.setBrightness(profile.getPreferredBrightness());
        }

        // Apply color
        tuyaService.setColor(profile.getPreferredColor());
        if (currentLightSettings != null) {
            currentLightSettings.setColor(profile.getPreferredColor());
        }

        // Apply schedule settings to local LightSettings model
        if (currentLightSettings != null) {
            currentLightSettings.setSunsetTime(profile.getPreferredSunsetTime());
            currentLightSettings.setBedtime(profile.getPreferredBedtime());
            currentLightSettings.setAutoScheduleEnabled(profile.isAutoScheduleEnabled());
            currentLightSettings.setUsername(profile.getUserName());
        }

        // Notify main activity about the new settings
        if (lightSettingsCallback != null && currentLightSettings != null) {
            lightSettingsCallback.accept(currentLightSettings);
        }

        // Update UI to reflect current speaker and their applied preferences
        updateCurrentSpeakerUI();
    }

    /**
     * Displays a dialog for enrolling a new speaker.
     */
    private void showEnrollmentDialog() {
        if (isRecording || isProcessingApiCall) {
            safeShowSnackbar("Another operation is already in progress.");
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            safeShowSnackbar("Audio permission not granted. Please grant it in settings.");
            checkAudioPermission();
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_enroll_speaker, null);

        TextInputEditText editName = dialogView.findViewById(R.id.editSpeakerName);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Enroll New Speaker")
                .setMessage("Enter your name and record voice samples. About 20 seconds of speech needed.")
                .setView(dialogView)
                .setPositiveButton("Start Enrollment", (dialog, which) -> {
                    String name = editName.getText().toString().trim();
                    if (!name.isEmpty()) {
                        startEnrollmentProcess(name);
                    } else {
                        safeShowSnackbar("Please enter a name for the speaker.");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Initiates the speaker enrollment process using Eagle
     */
    private void startEnrollmentProcess(String userName) {
        if (speakerService == null || !speakerService.isInitialized()) {
            String status = speakerService != null ? speakerService.getInitializationStatus() : "Service not available";
            safeShowSnackbar("Voice service not ready: " + status);
            return;
        }

        isProcessingApiCall = true;
        safeShowSnackbar("Creating profile for " + userName + "...");

        speakerService.createSpeakerProfile(userName, new EagleSpeakerIdentificationService.CreateProfileCallback() {
            @Override
            public void onProfileCreated(String profileId, String createdUserName) {
                Log.d(TAG, "Profile created: " + profileId + " for " + createdUserName);
                safeShowSnackbar("Profile created. Starting enrollment recording...");
                isProcessingApiCall = false;
                // Refresh the list to show the new profile
                refreshSpeakerList();
                // Start enrollment recording for this new profile
                enrollSpeaker(profileId, createdUserName, 20);
            }

            @Override
            public void onError(String error) {
                isProcessingApiCall = false;
                safeShowSnackbar("Failed to create profile: " + error);
                updateControlStates();
            }
        });
    }

    /**
     * Manages the voice enrollment process using Eagle
     */
    private void enrollSpeaker(String profileId, String userName, int remainingSeconds) {
        if (isRecording || isProcessingApiCall) {
            safeShowSnackbar("Already recording or processing another request.");
            return;
        }

        isRecording = true;
        isProcessingApiCall = true;
        updateRecordingUI(true, "🎤 Recording for " + userName + "... Speak clearly and naturally!");
        updateControlStates(); // ADDED: Update control states separately

        speakerService.enrollSpeaker(profileId, new EagleSpeakerIdentificationService.EnrollmentCallback() {
            @Override
            public void onRecordingStarted() {
                Log.d(TAG, "Enrollment recording started for " + userName);
                safeUpdateUI(() -> {
                    Toast.makeText(requireContext(), "📢 Start speaking now! Speak for at least 15 seconds.", Toast.LENGTH_LONG).show();
                    updateRecordingUI(true, "🗣️ Keep speaking... Need clear, natural speech.");
                    updateControlStates(); // ADDED: Update control states
                });
            }

            @Override
            public void onEnrollmentComplete(String profileId, String completedUserName) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states
                    refreshSpeakerList();
                    safeShowSnackbar("✅ Enrollment complete for " + completedUserName + "! You can now be identified.");
                    Toast.makeText(requireContext(), "🎉 " + completedUserName + " successfully enrolled!", Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onEnrollmentProgress(int remainingSeconds) {
                isRecording = false; // Recording chunk finished
                isProcessingApiCall = false;

                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states
                    refreshSpeakerList();
                    if (remainingSeconds > 0) {
                        safeShowSnackbar("📈 Progress made! Need " + remainingSeconds + " more seconds for " + userName);
                        updateRecordingUI(true, "🔄 Continue speaking... " + remainingSeconds + "s remaining");
                        updateControlStates(); // ADDED: Update control states again for recording state
                    } else {
                        safeShowSnackbar("Almost done! Keep speaking clearly...");
                        updateRecordingUI(true, "🎯 Final stage - keep speaking naturally!");
                        updateControlStates(); // ADDED: Update control states again for recording state
                    }
                });
            }

            @Override
            public void onError(String error) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    updateControlStates(); // ADDED: Update control states

                    // Provide helpful error messages
                    String userFriendlyError;
                    if (error.contains("Please speak for longer")) {
                        userFriendlyError = "Need more speech! Try speaking for 20-30 seconds continuously.";
                    } else if (error.contains("audio quality")) {
                        userFriendlyError = "Audio quality issue. Please speak closer to the microphone.";
                    } else if (error.contains("Multiple speakers")) {
                        userFriendlyError = "Only one person should speak during enrollment.";
                    } else {
                        userFriendlyError = "Enrollment error: " + error;
                    }

                    safeShowSnackbar("❌ " + userFriendlyError);
                    refreshSpeakerList();
                });
            }
        });
    }

    /**
     * Displays a dialog to edit a speaker's lighting preferences with intuitive color picker.
     */
    private void showPreferencesDialog(@NonNull SpeakerProfile profile) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_speaker_preferences, null);

        // Get UI controls
        com.google.android.material.slider.Slider sliderBrightness = dialogView.findViewById(R.id.sliderBrightness);
        TextView textBrightnessValue = dialogView.findViewById(R.id.textBrightnessValue);
        View selectedColorPreview = dialogView.findViewById(R.id.selectedColorPreview);
        TextView textSelectedColor = dialogView.findViewById(R.id.textSelectedColor);
        TextView textSelectedColorHex = dialogView.findViewById(R.id.textSelectedColorHex);
        TextInputEditText editCustomColor = dialogView.findViewById(R.id.editCustomColor);
        TextInputEditText editSunsetTime = dialogView.findViewById(R.id.editSunsetTime);
        TextInputEditText editBedtime = dialogView.findViewById(R.id.editBedtime);
        com.google.android.material.materialswitch.MaterialSwitch switchAutoSchedule = dialogView.findViewById(R.id.switchAutoSchedule);

        // Get circular color picker
        com.smart.smartbulb.CircularColorPicker circularColorPicker = dialogView.findViewById(R.id.circularColorPicker);

        // Color picker views (for quick presets)
        View colorWarmWhite = dialogView.findViewById(R.id.colorWarmWhite);
        View colorSoftYellow = dialogView.findViewById(R.id.colorSoftYellow);
        View colorPureWhite = dialogView.findViewById(R.id.colorPureWhite);
        View colorCoolWhite = dialogView.findViewById(R.id.colorCoolWhite);
        View colorSoftBlue = dialogView.findViewById(R.id.colorSoftBlue);
        View colorLavender = dialogView.findViewById(R.id.colorLavender);
        View colorSoftRed = dialogView.findViewById(R.id.colorSoftRed);
        View colorSoftGreen = dialogView.findViewById(R.id.colorSoftGreen);

        // Color mapping for quick presets
        Map<View, ColorInfo> colorMap = new HashMap<>();
        colorMap.put(colorWarmWhite, new ColorInfo("#FFBB66", "Warm White"));
        colorMap.put(colorSoftYellow, new ColorInfo("#FFDD99", "Soft Yellow"));
        colorMap.put(colorPureWhite, new ColorInfo("#FFFFFF", "Pure White"));
        colorMap.put(colorCoolWhite, new ColorInfo("#BBEEFF", "Cool White"));
        colorMap.put(colorSoftBlue, new ColorInfo("#99CCFF", "Soft Blue"));
        colorMap.put(colorLavender, new ColorInfo("#CC99FF", "Lavender"));
        colorMap.put(colorSoftRed, new ColorInfo("#FFAAAA", "Soft Red"));
        colorMap.put(colorSoftGreen, new ColorInfo("#AAFFAA", "Soft Green"));

        // Track selected color view for highlighting
        final View[] selectedColorView = {null};

        // Function to update color selection highlights
        Runnable updateColorSelection = () -> {
            // Reset all color views to normal state
            for (View colorView : colorMap.keySet()) {
                try {
                    colorView.setBackgroundResource(R.drawable.color_picker_item);
                    ColorInfo info = colorMap.get(colorView);
                    if (info != null) {
                        colorView.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(info.hex)));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting color view background", e);
                }
            }

            // Highlight selected color
            if (selectedColorView[0] != null) {
                try {
                    selectedColorView[0].setBackgroundResource(R.drawable.color_picker_item_selected);
                    ColorInfo info = colorMap.get(selectedColorView[0]);
                    if (info != null) {
                        selectedColorView[0].setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(info.hex)));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting selected color view background", e);
                }
            }
        };

        // Function to update all color displays when color changes
        Consumer<ColorInfo> updateColorDisplays = (colorInfo) -> {
            try {
                // Update preview
                selectedColorPreview.setBackgroundColor(Color.parseColor(colorInfo.hex));
                textSelectedColor.setText(colorInfo.name);
                textSelectedColorHex.setText(colorInfo.hex);

                // Update custom color input
                if (editCustomColor != null) {
                    editCustomColor.setText(colorInfo.hex);
                }

                // Update circular color picker
                if (circularColorPicker != null) {
                    circularColorPicker.setColor(colorInfo.hex);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating color displays", e);
            }
        };

        // Setup circular color picker listener
        if (circularColorPicker != null) {
            circularColorPicker.setOnColorSelectedListener(new com.smart.smartbulb.CircularColorPicker.OnColorSelectedListener() {
                @Override
                public void onColorSelected(int color, String hexColor) {
                    ColorInfo colorInfo = new ColorInfo(hexColor, "Custom Color");

                    // Clear preset selection
                    selectedColorView[0] = null;
                    updateColorSelection.run();

                    // Update displays (but don't update circular picker to avoid recursion)
                    try {
                        selectedColorPreview.setBackgroundColor(color);
                        textSelectedColor.setText(colorInfo.name);
                        textSelectedColorHex.setText(hexColor);
                        if (editCustomColor != null) {
                            editCustomColor.setText(hexColor);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error updating color from circular picker", e);
                    }
                }
            });
        }

        // Set up color picker click listeners for quick presets
        for (Map.Entry<View, ColorInfo> entry : colorMap.entrySet()) {
            View colorView = entry.getKey();
            ColorInfo colorInfo = entry.getValue();

            colorView.setOnClickListener(v -> {
                selectedColorView[0] = colorView;
                updateColorSelection.run();
                updateColorDisplays.accept(colorInfo);
            });
        }

        // Populate controls with current profile data
        if (sliderBrightness != null) {
            sliderBrightness.setValue(profile.getPreferredBrightness());
            if (textBrightnessValue != null) {
                textBrightnessValue.setText(profile.getPreferredBrightness() + "%");
            }
        }

        // Set up brightness slider listener
        if (sliderBrightness != null && textBrightnessValue != null) {
            sliderBrightness.addOnChangeListener((slider, value, fromUser) -> {
                textBrightnessValue.setText((int) value + "%");
            });
        }

        // Set initial color selection
        String currentColor = profile.getPreferredColor();
        boolean colorFound = false;

        // Check if current color matches any preset
        for (Map.Entry<View, ColorInfo> entry : colorMap.entrySet()) {
            if (entry.getValue().hex.equalsIgnoreCase(currentColor)) {
                selectedColorView[0] = entry.getKey();
                updateColorDisplays.accept(entry.getValue());
                colorFound = true;
                break;
            }
        }

        if (!colorFound) {
            // Custom color - just update displays
            updateColorDisplays.accept(new ColorInfo(currentColor, "Custom Color"));
        }

        updateColorSelection.run();

        // Set up custom color input with validation
        if (editCustomColor != null) {
            editCustomColor.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    String customColor = s.toString().trim();
                    if (isValidHexColor(customColor)) {
                        try {
                            Color.parseColor(customColor);
                            selectedColorView[0] = null; // Clear preset selection
                            updateColorSelection.run();

                            ColorInfo colorInfo = new ColorInfo(customColor, "Custom Color");
                            // Update preview and circular picker
                            selectedColorPreview.setBackgroundColor(Color.parseColor(customColor));
                            textSelectedColor.setText(colorInfo.name);
                            textSelectedColorHex.setText(customColor);

                            if (circularColorPicker != null) {
                                circularColorPicker.setColor(customColor);
                            }
                        } catch (Exception e) {
                            // Invalid color format - ignore
                        }
                    }
                }
            });
        }

        // Set time fields
        if (editSunsetTime != null) {
            editSunsetTime.setText(profile.getPreferredSunsetTime());
        }
        if (editBedtime != null) {
            editBedtime.setText(profile.getPreferredBedtime());
        }
        if (switchAutoSchedule != null) {
            switchAutoSchedule.setChecked(profile.isAutoScheduleEnabled());
        }

        // Create and show dialog
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(profile.getUserName() + "'s Preferences")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    saveSpeakerPreferencesFromDialog(profile, dialogView);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

/**
 * Helper method to validate hex color format
 */

private boolean isValidHexColor(String color) {
    if (color == null || color.isEmpty()) return false;

    // Add # if missing
    if (!color.startsWith("#")) {
        color = "#" + color;
    }

    // Check format: #RRGGBB
    return color.matches("^#[0-9A-Fa-f]{6}$");
}

    /**
     * Saves the updated speaker preferences from the dialog with enhanced color picker support.
     */
    private void saveSpeakerPreferencesFromDialog(@NonNull SpeakerProfile profile, @NonNull View dialogView) {
        try {
            // Extract values from dialog controls
            com.google.android.material.slider.Slider sliderBrightness = dialogView.findViewById(R.id.sliderBrightness);
            TextInputEditText editCustomColor = dialogView.findViewById(R.id.editCustomColor);
            TextInputEditText editSunsetTime = dialogView.findViewById(R.id.editSunsetTime);
            TextInputEditText editBedtime = dialogView.findViewById(R.id.editBedtime);
            com.google.android.material.materialswitch.MaterialSwitch switchAutoSchedule = dialogView.findViewById(R.id.switchAutoSchedule);
            com.smart.smartbulb.CircularColorPicker circularColorPicker = dialogView.findViewById(R.id.circularColorPicker);

            // Default values
            int newBrightness = profile.getPreferredBrightness();
            String newColor = profile.getPreferredColor();
            String newSunsetTime = profile.getPreferredSunsetTime();
            String newBedtime = profile.getPreferredBedtime();
            boolean newAutoSchedule = profile.isAutoScheduleEnabled();

            // Extract brightness
            if (sliderBrightness != null) {
                newBrightness = (int) sliderBrightness.getValue();
            }

            // Extract color - prioritize circular color picker, then custom input
            if (circularColorPicker != null) {
                String circularPickerColor = circularColorPicker.getSelectedColorHex();
                if (isValidHexColor(circularPickerColor)) {
                    newColor = circularPickerColor.toUpperCase();
                }
            } else if (editCustomColor != null && editCustomColor.getText() != null) {
                String colorText = editCustomColor.getText().toString().trim();
                if (isValidHexColor(colorText)) {
                    if (!colorText.startsWith("#")) {
                        colorText = "#" + colorText;
                    }
                    try {
                        Color.parseColor(colorText); // Validate
                        newColor = colorText.toUpperCase();
                    } catch (Exception e) {
                        Log.w(TAG, "Invalid color format: " + colorText);
                    }
                }
            }

            // Extract sunset time
            if (editSunsetTime != null && editSunsetTime.getText() != null) {
                String timeText = editSunsetTime.getText().toString().trim();
                if (!timeText.isEmpty() && timeText.matches("^\\d{1,2}:\\d{2}$")) {
                    newSunsetTime = timeText;
                }
            }

            // Extract bedtime
            if (editBedtime != null && editBedtime.getText() != null) {
                String timeText = editBedtime.getText().toString().trim();
                if (!timeText.isEmpty() && timeText.matches("^\\d{1,2}:\\d{2}$")) {
                    newBedtime = timeText;
                }
            }

            // Extract auto schedule
            if (switchAutoSchedule != null) {
                newAutoSchedule = switchAutoSchedule.isChecked();
            }

            // Update the speaker preferences
            speakerService.updateSpeakerPreferences(
                    profile.getProfileId(),
                    newBrightness,
                    newColor,
                    newSunsetTime,
                    newBedtime,
                    newAutoSchedule
            );

            refreshSpeakerList();
            safeShowSnackbar("✅ Preferences updated for " + profile.getUserName());

            // Show a summary of what was saved
            String summary = String.format("💡 %s's preferences updated:\n🔆 Brightness: %d%%\n🎨 Color: %s\n⏰ Sunset: %s, Bedtime: %s",
                    profile.getUserName(), newBrightness, getColorName(newColor), newSunsetTime, newBedtime);
            Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "Error saving speaker preferences", e);
            safeShowSnackbar("❌ Error saving preferences: " + e.getMessage());
        }
    }

    /**
     * Helper class to store color information
     */
    private static class ColorInfo {
        final String hex;
        final String name;

        ColorInfo(String hex, String name) {
            this.hex = hex;
            this.name = name;
        }
    }
    /**
     * Saves the updated speaker preferences with color picker support.
     */
    private void saveSpeakerPreferences(@NonNull SpeakerProfile profile, @NonNull View dialogView) {
        try {
            // Extract values from dialog controls
            com.google.android.material.slider.Slider sliderBrightness = dialogView.findViewById(R.id.sliderBrightness);
            TextInputEditText editCustomColor = dialogView.findViewById(R.id.editCustomColor);
            TextInputEditText editSunsetTime = dialogView.findViewById(R.id.editSunsetTime);
            TextInputEditText editBedtime = dialogView.findViewById(R.id.editBedtime);
            com.google.android.material.materialswitch.MaterialSwitch switchAutoSchedule = dialogView.findViewById(R.id.switchAutoSchedule);

            int newBrightness = profile.getPreferredBrightness();
            String newColor = profile.getPreferredColor();
            String newSunsetTime = profile.getPreferredSunsetTime();
            String newBedtime = profile.getPreferredBedtime();
            boolean newAutoSchedule = profile.isAutoScheduleEnabled();

            // Extract brightness
            if (sliderBrightness != null) {
                newBrightness = (int) sliderBrightness.getValue();
            }

            // Extract color from custom color input
            if (editCustomColor != null && editCustomColor.getText() != null) {
                String colorText = editCustomColor.getText().toString().trim();
                if (!colorText.isEmpty()) {
                    if (!colorText.startsWith("#")) {
                        colorText = "#" + colorText;
                    }
                    // Validate hex color format
                    if (colorText.matches("^#[0-9A-Fa-f]{6}$")) {
                        try {
                            Color.parseColor(colorText); // Test if it's a valid color
                            newColor = colorText.toUpperCase();
                        } catch (Exception e) {
                            Log.w(TAG, "Invalid color format: " + colorText);
                        }
                    }
                }
            }

            // Extract sunset time
            if (editSunsetTime != null && editSunsetTime.getText() != null) {
                String timeText = editSunsetTime.getText().toString().trim();
                if (!timeText.isEmpty() && timeText.matches("^\\d{1,2}:\\d{2}$")) {
                    newSunsetTime = timeText;
                }
            }

            // Extract bedtime
            if (editBedtime != null && editBedtime.getText() != null) {
                String timeText = editBedtime.getText().toString().trim();
                if (!timeText.isEmpty() && timeText.matches("^\\d{1,2}:\\d{2}$")) {
                    newBedtime = timeText;
                }
            }

            // Extract auto schedule
            if (switchAutoSchedule != null) {
                newAutoSchedule = switchAutoSchedule.isChecked();
            }

            // Update the speaker preferences
            speakerService.updateSpeakerPreferences(
                    profile.getProfileId(),
                    newBrightness,
                    newColor,
                    newSunsetTime,
                    newBedtime,
                    newAutoSchedule
            );

            refreshSpeakerList();
            safeShowSnackbar("✅ Preferences updated for " + profile.getUserName());

            // Show a summary of what was saved
            String summary = String.format("💡 %s's preferences updated:\n🔆 Brightness: %d%%\n🎨 Color: %s\n⏰ Sunset: %s, Bedtime: %s",
                    profile.getUserName(), newBrightness, getColorName(newColor), newSunsetTime, newBedtime);
            Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "Error saving speaker preferences", e);
            safeShowSnackbar("❌ Error saving preferences: " + e.getMessage());
        }
    }

    /**
     * Confirms and then initiates deletion of a speaker profile.
     */
    private void confirmDeleteSpeaker(@NonNull SpeakerProfile profile) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Speaker Profile")
                .setMessage("Are you sure you want to delete " + profile.getUserName() + "'s profile? This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteSpeaker(profile);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Deletes a speaker profile.
     */
    private void deleteSpeaker(@NonNull SpeakerProfile profile) {
        isProcessingApiCall = true;
        safeShowSnackbar("Deleting profile for " + profile.getUserName() + "...");

        speakerService.deleteSpeakerProfile(profile.getProfileId(),
                new EagleSpeakerIdentificationService.DeleteProfileCallback() {
                    @Override
                    public void onProfileDeleted(String profileId) {
                        isProcessingApiCall = false;
                        safeUpdateUI(() -> {
                            refreshSpeakerList();
                            if (currentSpeaker != null && currentSpeaker.getProfileId().equals(profileId)) {
                                currentSpeaker = null;
                                updateCurrentSpeakerUI();
                            }
                            safeShowSnackbar("Speaker profile deleted successfully.");
                        });
                    }

                    @Override
                    public void onError(String error) {
                        isProcessingApiCall = false;
                        safeUpdateUI(() -> {
                            safeShowSnackbar("Failed to delete profile: " + error);
                            updateControlStates();
                        });
                    }
                });
    }

    /**
     * Shows dialog to clear all speaker profiles (for reset/debugging)
     */
    private void showClearProfilesDialog() {
        int profileCount = speakerService.getEnrolledSpeakers().size();
        if (profileCount == 0) {
            safeShowSnackbar("No profiles to clear");
            return;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("⚠️ Clear All Profiles")
                .setMessage("This will permanently delete all " + profileCount + " enrolled speaker profiles. This action cannot be undone.\n\nAre you sure?")
                .setPositiveButton("Clear All", (dialog, which) -> {
                    speakerService.clearStoredProfiles();
                    refreshSpeakerList();
                    currentSpeaker = null;
                    updateCurrentSpeakerUI();
                    safeShowSnackbar("✅ All speaker profiles cleared");
                    Toast.makeText(requireContext(), "All profiles deleted. You'll need to enroll again.", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void processVoiceCommand() {
        safeShowSnackbar("Voice commands are under development!");
        // Future: Implement voice commands like "dim lights", "turn on sunset mode", etc.
    }

    // --- UI Update Methods ---
    private void updateUI() {
        updateControlStates();
        updateCurrentSpeakerUI();
        refreshSpeakerList();
    }

    /**
     * Enables/disables UI controls based on recording/processing state and Tuya connection.
     */
    // Replace these two methods in your VoiceControlFragment.java

    /**
     * Enables/disables UI controls based on recording/processing state and Tuya connection.
     */
    private void updateControlStates() {
        boolean isAudioPermissionGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean isTuyaConnected = tuyaService != null && tuyaService.isConnected();
        boolean isEagleReady = speakerService != null && speakerService.isInitialized() && !speakerService.isInitializing();
        boolean canControl = isTuyaConnected && isAudioPermissionGranted && isEagleReady;

        Log.d(TAG, String.format("Control states - Audio: %s, Tuya: %s, Eagle: %s, CanControl: %s",
                isAudioPermissionGranted, isTuyaConnected, isEagleReady, canControl));

        // Disable interaction if recording or an API call is in progress
        buttonIdentify.setEnabled(canControl && !isRecording && !isProcessingApiCall);
        buttonEnrollNew.setEnabled(canControl && !isRecording && !isProcessingApiCall);
        fabVoiceCommand.setEnabled(canControl && !isRecording && !isProcessingApiCall);

        // Show initialization status ONLY if Eagle is initializing
        if (speakerService != null && speakerService.isInitializing()) {
            String status = speakerService.getInitializationStatus();
            // REMOVED: updateRecordingUI call to break circular reference
            // Instead, directly update the recording UI elements
            if (progressRecording != null && recordingStatusContainer != null && textRecordingStatus != null) {
                progressRecording.setVisibility(View.VISIBLE);
                textRecordingStatus.setText(status);
                recordingStatusContainer.setVisibility(View.VISIBLE);
            }
        } else if (speakerService != null && !speakerService.isInitialized()) {
            // Show error state but don't use recording UI for this
            String status = speakerService.getInitializationStatus();
            Log.w(TAG, "Eagle not initialized: " + status);
            // Hide any initialization UI if Eagle failed to initialize
            if (progressRecording != null && recordingStatusContainer != null) {
                progressRecording.setVisibility(View.GONE);
                recordingStatusContainer.setVisibility(View.GONE);
            }
        } else {
            // Eagle is ready - hide initialization UI
            if (!isRecording && !isProcessingApiCall && progressRecording != null && recordingStatusContainer != null) {
                progressRecording.setVisibility(View.GONE);
                recordingStatusContainer.setVisibility(View.GONE);
            }
        }

        // Ensure RecyclerView items are also properly enabled/disabled
        if (speakerAdapter != null) {
            speakerAdapter.setInteractionEnabled(!isRecording && !isProcessingApiCall && isEagleReady);
        }
    }

    /**
     * Shows/hides the recording status UI.
     * FIXED: Removed circular call to updateControlStates()
     */



    /**
     * Shows/hides the recording status UI.
     */
    private void updateRecordingUI(boolean recording, String statusText) {
        if (recordingStatusContainer != null && progressRecording != null && textRecordingStatus != null) {
            progressRecording.setVisibility(recording ? View.VISIBLE : View.GONE);
            textRecordingStatus.setText(statusText);
            recordingStatusContainer.setVisibility(recording ? View.VISIBLE : View.GONE);
        }

        // REMOVED: updateControlStates() call to break circular reference
        // The control states should be updated separately when needed

        Log.d(TAG, "Recording UI updated - Recording: " + recording + ", Status: " + statusText);
    }

    /**
     * Updates the UI displaying the currently identified speaker and their preferences.
     */
    private void updateCurrentSpeakerUI() {
        if (currentSpeaker != null) {
            cardCurrentSpeaker.setVisibility(View.VISIBLE);
            textCurrentSpeaker.setText("Current Speaker: " + currentSpeaker.getUserName());

            String preferences = String.format(
                    "Brightness: %d%%\nColor: %s\nSunset: %s\nBedtime: %s\nAuto Schedule: %s",
                    currentSpeaker.getPreferredBrightness(),
                    getColorName(currentSpeaker.getPreferredColor()),
                    currentSpeaker.getPreferredSunsetTime(),
                    currentSpeaker.getPreferredBedtime(),
                    currentSpeaker.isAutoScheduleEnabled() ? "On" : "Off"
            );
            textSpeakerPreferences.setText(preferences);
        } else {
            cardCurrentSpeaker.setVisibility(View.GONE);
        }
    }

    /**
     * Refreshes the list of enrolled speakers displayed in the RecyclerView.
     */
    private void refreshSpeakerList() {
        if (speakerService == null) {
            Log.w(TAG, "SpeakerService is null, cannot refresh speaker list");
            return;
        }

        List<SpeakerProfile> speakers = speakerService.getEnrolledSpeakers();
        Log.d(TAG, "Refreshing speaker list with " + speakers.size() + " profiles");

        if (speakerAdapter != null) {
            speakerAdapter.updateSpeakers(speakers);
        }

        // Show/hide empty state based on whether there are any profiles
        if (speakers.isEmpty()) {
            if (cardEmptyState != null) {
                cardEmptyState.setVisibility(View.VISIBLE);
            }
            if (recyclerSpeakers != null) {
                recyclerSpeakers.setVisibility(View.GONE);
            }
            if (textEnrolledTitle != null) {
                textEnrolledTitle.setVisibility(View.GONE);
            }
            Log.d(TAG, "Showing empty state - no profiles found");
        } else {
            if (cardEmptyState != null) {
                cardEmptyState.setVisibility(View.GONE);
            }
            if (recyclerSpeakers != null) {
                recyclerSpeakers.setVisibility(View.VISIBLE);
            }
            if (textEnrolledTitle != null) {
                textEnrolledTitle.setVisibility(View.VISIBLE);
            }
            Log.d(TAG, "Showing speaker list with " + speakers.size() + " profiles");
        }
    }


    /**
     * Helper to get a human-readable name for a hex color.
     */
    private String getColorName(String hexColor) {
        if (hexColor == null) return "N/A";
        switch (hexColor.toUpperCase()) {
            case "#FFBB66": return "Warm White";
            case "#FFDD99": return "Soft Yellow";
            case "#FFFFFF": return "Pure White";
            case "#BBEEFF": return "Cool White";
            case "#99CCFF": return "Soft Blue";
            case "#CC99FF": return "Lavender";
            case "#FFAAAA": return "Soft Red";
            case "#AAFFAA": return "Soft Green";
            default: return hexColor;
        }
    }

    // --- Safe UI Update Helpers ---
    private void safeUpdateUI(Runnable updateTask) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(updateTask);
        }
    }

    private void safeShowSnackbar(String message) {
        safeUpdateUI(() -> {
            if (rootView != null) {
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Public method to set a callback for light settings changes.
     */
    public void setLightSettingsCallback(Consumer<LightSettings> callback) {
        this.lightSettingsCallback = callback;
    }

    // --- Lifecycle Methods ---
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "VoiceControlFragment onResume");

        // Reconnect Tuya if needed
        if (tuyaService != null && !tuyaService.isConnected()) {
            tuyaService.connect();
        }

        // Always refresh UI state
        updateUI();

        // Refresh speaker list to ensure it's up to date
        refreshSpeakerList();
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "VoiceControlFragment onPause");
        // Stop any ongoing recording to prevent resource leaks
        if (isRecording && speakerService != null) {
            speakerService.stopRecording();
            isRecording = false;
            isProcessingApiCall = false;
            updateRecordingUI(false, "");
            updateControlStates(); // ADDED: Update control states after stopping
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "VoiceControlFragment onDestroyView");

        // Clean up services
        if (tuyaService != null) {
            tuyaService.disconnect();
        }

        if (speakerService != null) {
            speakerService.cleanup();
        }
    }

    /**
     * Speaker Adapter for RecyclerView
     */
    private static class SpeakerAdapter extends RecyclerView.Adapter<SpeakerAdapter.SpeakerViewHolder> {

        public interface SpeakerActionListener {
            void onSpeakerSelected(SpeakerProfile profile);
            void onEditPreferences(SpeakerProfile profile);
            void onDeleteSpeaker(SpeakerProfile profile);
            void onEnrollMore(SpeakerProfile profile);
        }

        private List<SpeakerProfile> speakers;
        private SpeakerActionListener listener;
        private boolean interactionEnabled = true;

        public SpeakerAdapter(List<SpeakerProfile> speakers, SpeakerActionListener listener) {
            this.speakers = speakers;
            this.listener = listener;
        }

        public void updateSpeakers(List<SpeakerProfile> speakers) {
            this.speakers = speakers;
            notifyDataSetChanged();
        }

        public void setInteractionEnabled(boolean enabled) {
            if (this.interactionEnabled != enabled) {
                this.interactionEnabled = enabled;
                notifyDataSetChanged();
            }
        }

        @NonNull
        @Override
        public SpeakerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_speaker_profile, parent, false);
            return new SpeakerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SpeakerViewHolder holder, int position) {
            SpeakerProfile profile = speakers.get(position);
            holder.bind(profile, listener, interactionEnabled);
        }

        @Override
        public int getItemCount() {
            return speakers.size();
        }

        static class SpeakerViewHolder extends RecyclerView.ViewHolder {
            private TextView textName;
            private TextView textLastSeen;
            private TextView textEnrollmentStatus;
            private View colorIndicator;
            private Button buttonApply;
            private Button buttonEdit;
            private Button buttonDelete;
            private Button buttonEnrollMore;

            public SpeakerViewHolder(@NonNull View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.textSpeakerName);
                textLastSeen = itemView.findViewById(R.id.textLastSeen);
                textEnrollmentStatus = itemView.findViewById(R.id.textEnrollmentStatus);
                colorIndicator = itemView.findViewById(R.id.colorIndicator);
                buttonApply = itemView.findViewById(R.id.buttonApply);
                buttonEdit = itemView.findViewById(R.id.buttonEdit);
                buttonDelete = itemView.findViewById(R.id.buttonDelete);
                buttonEnrollMore = itemView.findViewById(R.id.buttonEnrollMore);
            }

            public void bind(@NonNull SpeakerProfile profile, @NonNull SpeakerActionListener listener, boolean interactionEnabled) {
                textName.setText(profile.getUserName());

                // Format last seen time
                if (profile.getLastIdentified() > 0) {
                    long timeDiff = System.currentTimeMillis() - profile.getLastIdentified();
                    String lastSeen;
                    if (timeDiff < 60000) {
                        lastSeen = "Just now";
                    } else if (timeDiff < 3600000) {
                        lastSeen = (timeDiff / 60000) + " min ago";
                    } else if (timeDiff < 86400000) {
                        lastSeen = (timeDiff / 3600000) + " hours ago";
                    } else {
                        lastSeen = (timeDiff / 86400000) + " days ago";
                    }
                    textLastSeen.setText("Last identified: " + lastSeen);
                    textLastSeen.setVisibility(View.VISIBLE);
                } else {
                    textLastSeen.setVisibility(View.GONE);
                }

                // Display enrollment status
                String status = profile.getEnrollmentStatus();
                textEnrollmentStatus.setText("Status: " + status);
                if ("Enrolled".equalsIgnoreCase(status)) {
                    textEnrollmentStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_green_dark));
                    buttonApply.setVisibility(View.VISIBLE);
                    buttonEnrollMore.setVisibility(View.GONE);
                } else {
                    textEnrollmentStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_orange_dark));
                    textEnrollmentStatus.append(" (" + profile.getRemainingEnrollmentsSpeechLength() + "s needed)");
                    buttonApply.setVisibility(View.GONE);
                    buttonEnrollMore.setVisibility(View.VISIBLE);
                }

                // Set color indicator
                try {
                    colorIndicator.setBackgroundColor(Color.parseColor(profile.getPreferredColor()));
                } catch (Exception e) {
                    Log.e("SpeakerViewHolder", "Invalid color hex: " + profile.getPreferredColor(), e);
                    colorIndicator.setBackgroundColor(Color.WHITE);
                }

                // Set button listeners and enable state
                buttonApply.setOnClickListener(v -> listener.onSpeakerSelected(profile));
                buttonEdit.setOnClickListener(v -> listener.onEditPreferences(profile));
                buttonDelete.setOnClickListener(v -> listener.onDeleteSpeaker(profile));
                buttonEnrollMore.setOnClickListener(v -> listener.onEnrollMore(profile));

                // Control button and item clickability
                itemView.setEnabled(interactionEnabled);
                buttonApply.setEnabled(interactionEnabled && "Enrolled".equalsIgnoreCase(status));
                buttonEdit.setEnabled(interactionEnabled);
                buttonDelete.setEnabled(interactionEnabled);
                buttonEnrollMore.setEnabled(interactionEnabled && !"Enrolled".equalsIgnoreCase(status));
            }
        }
    }
}