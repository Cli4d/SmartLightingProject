package com.smart.smartbulb.fragments;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast; // Added for simple toasts

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
import com.smart.smartbulb.services.AzureSpeakerIdentificationService;
import com.smart.smartbulb.services.AzureSpeakerIdentificationService.SpeakerProfile;
import com.smart.smartbulb.services.TuyaCloudApiService;

import java.util.List;
import java.util.function.Consumer;

/**
 * Voice Control Fragment with Azure Speaker Identification
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
    private View recordingStatusContainer; // Added for the entire recording UI group

    // Services
    private AzureSpeakerIdentificationService speakerService;
    private TuyaCloudApiService tuyaService;

    // State
    private LightSettings currentLightSettings; // Holds the current state of the bulb
    private SpeakerProfile currentSpeaker; // The last identified speaker
    private Consumer<LightSettings> lightSettingsCallback; // Callback to activity/main fragment
    private volatile boolean isRecording = false; // Tracks if audio recording is active
    private volatile boolean isProcessingApiCall = false; // Tracks if an API call is in progress

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

        Log.d(TAG, "VoiceControlFragment onViewCreated");

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
        recordingStatusContainer = rootView.findViewById(R.id.recordingStatusContainer); // Ensure this ID exists in your layout
    }

    private void initializeServices() {
        // Initialize speaker service
        speakerService = new AzureSpeakerIdentificationService(requireContext());

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
                    // Only update current speaker UI if this change is due to speaker preferences
                    // Otherwise, the main fragment will handle it.
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

        // Connect to Tuya on startup or resume
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

        // Voice command FAB
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
                // Allows user to continue enrolling if a profile isn't fully enrolled
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
            // Permission already granted, enable buttons
            updateControlStates();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); // Call super method
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                safeShowSnackbar("Audio recording permission granted!");
                updateControlStates(); // Enable buttons after permission is granted
            } else {
                safeShowSnackbar("Audio permission is required for voice control features.");
                // Disable functionality if permission is denied
                buttonIdentify.setEnabled(false);
                buttonEnrollNew.setEnabled(false);
                fabVoiceCommand.setEnabled(false);
            }
        }
    }

    /**
     * Initiates speaker identification process.
     */
    private void identifySpeaker() {
        if (isRecording || isProcessingApiCall) {
            safeShowSnackbar("Another operation is already in progress.");
            return;
        }

        // Check if there are any *enrolled* profiles to identify against
        boolean hasEnrolledProfiles = speakerService.getEnrolledSpeakers().stream()
                .anyMatch(p -> "Enrolled".equalsIgnoreCase(p.getEnrollmentStatus()));

        if (!hasEnrolledProfiles) {
            safeShowSnackbar("No fully enrolled speakers to identify. Please enroll a profile first.");
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            safeShowSnackbar("Audio permission not granted. Please grant it in settings.");
            checkAudioPermission(); // Request permission again
            return;
        }

        isRecording = true;
        isProcessingApiCall = true;
        updateRecordingUI(true, "Listening... Speak for 4 seconds for identification.");

        speakerService.identifySpeaker(new AzureSpeakerIdentificationService.SpeakerIdentificationCallback() {
            @Override
            public void onSpeakerIdentified(SpeakerProfile profile) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
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
                    safeShowSnackbar("Identification failed: " + error);
                    currentSpeaker = null; // Clear current speaker on failure
                    updateCurrentSpeakerUI();
                });
            }

            @Override
            public void onError(String error) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    safeShowSnackbar("Error during identification: " + error);
                    currentSpeaker = null; // Clear current speaker on error
                    updateCurrentSpeakerUI();
                });
            }
        });
    }

    /**
     * Applies the speaker's lighting preferences to the Tuya device and updates UI.
     * @param profile The SpeakerProfile whose preferences are to be applied.
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
            currentLightSettings.setUsername(profile.getUserName()); // Set username in LightSettings
        }


        // Notify main activity or other parts of the app about the new settings
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
            checkAudioPermission(); // Request permission again
            return;
        }

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_enroll_speaker, null); // Make sure this layout exists

        TextInputEditText editName = dialogView.findViewById(R.id.editSpeakerName);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Enroll New Speaker")
                .setMessage("Enter your name and record initial voice samples. Total of 20 seconds speech needed.")
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
     * Initiates the speaker enrollment process: first creates profile, then records audio.
     * @param userName The name of the speaker to enroll.
     */
    private void startEnrollmentProcess(String userName) {
        isProcessingApiCall = true; // Set processing flag for profile creation
        safeShowSnackbar("Creating profile for " + userName + "...");

        speakerService.createSpeakerProfile(userName, new AzureSpeakerIdentificationService.CreateProfileCallback() {
            @Override
            public void onProfileCreated(String profileId) {

            }

            @Override
            public void onProfileCreated(String profileId, String createdUserName) {
                Log.d(TAG, "Profile created: " + profileId + " for " + createdUserName);
                safeShowSnackbar("Profile created. Now starting enrollment recording...");
                isProcessingApiCall = false; // Reset after profile creation, as recording has its own state
                // Now start enrollment recording for this new profile
                enrollSpeaker(profileId, createdUserName, 20); // Initial enrollment needs 20 seconds
            }

            @Override
            public void onError(String error) {
                isProcessingApiCall = false;
                safeShowSnackbar("Failed to create profile: " + error);
                updateControlStates(); // Re-enable buttons
            }
        });
    }

    /**
     * Manages the actual voice enrollment process.
     * @param profileId The ID of the speaker profile.
     * @param userName The name of the speaker.
     * @param remainingSeconds The number of remaining seconds of speech required for enrollment.
     */
    private void enrollSpeaker(String profileId, String userName, int remainingSeconds) {
        if (isRecording || isProcessingApiCall) {
            safeShowSnackbar("Already recording or processing another request.");
            return;
        }

        isRecording = true;
        isProcessingApiCall = true;
        updateRecordingUI(true, "Recording for " + userName + "... Need " + remainingSeconds + "s more speech.");

        speakerService.enrollSpeaker(profileId, new AzureSpeakerIdentificationService.EnrollmentCallback() {
            @Override
            public void onRecordingStarted() {
                Log.d(TAG, "Enrollment recording started.");
                safeUpdateUI(() -> Toast.makeText(requireContext(), "Start speaking now...", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onEnrollmentComplete(String profileId, String completedUserName) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    refreshSpeakerList(); // Refresh list to show updated status
                    safeShowSnackbar("Enrollment complete for " + completedUserName + "!");
                });
            }

            @Override
            public void onEnrollmentComplete(String profileId) {

            }

            @Override
            public void onEnrollmentProgress(int remainingSeconds) {
                isRecording = false; // Recording chunk finished, but enrollment isn't
                isProcessingApiCall = false; // API call for this chunk is done

                safeUpdateUI(() -> {
                    updateRecordingUI(false, ""); // Hide recording UI for a moment
                    refreshSpeakerList(); // Update list to show new remaining time
                    safeShowSnackbar("Enrollment progress: Need " + remainingSeconds + " more seconds for " + userName + ".");

                    // Option to prompt for more recording or automatically start next chunk
                    // For simplicity, we'll prompt the user to click "Enroll More" from the list item
                });
            }

            @Override
            public void onError(String error) {
                isRecording = false;
                isProcessingApiCall = false;
                safeUpdateUI(() -> {
                    updateRecordingUI(false, "");
                    safeShowSnackbar("Enrollment error for " + userName + ": " + error);
                    refreshSpeakerList(); // Refresh list to show potential status changes
                });
            }
        });
    }

    /**
     * Displays a dialog to edit a speaker's lighting preferences.
     * @param profile The SpeakerProfile to edit.
     */
    private void showPreferencesDialog(@NonNull SpeakerProfile profile) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_speaker_preferences, null); // Make sure this layout exists

        // --- IMPORTANT: You need to implement these UI controls in dialog_speaker_preferences.xml ---
        // For example:
        // Slider for brightness: Slider sliderBrightness = dialogView.findViewById(R.id.sliderBrightness);
        // Color picker: Some custom view or library for color selection
        // Time pickers for sunset/bedtime: TextInputEditText editSunsetTime, editBedtime;
        // Toggle for auto schedule: Switch switchAutoSchedule;

        // Populate controls with current profile data
        // Example: if (sliderBrightness != null) sliderBrightness.setValue(profile.getPreferredBrightness());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(profile.getUserName() + "'s Preferences")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    // Save preferences by extracting values from dialogView controls
                    saveSpeakerPreferences(profile, dialogView);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Saves the updated speaker preferences.
     * @param profile The SpeakerProfile to update.
     * @param dialogView The view containing the preference input controls.
     */
    private void saveSpeakerPreferences(@NonNull SpeakerProfile profile, @NonNull View dialogView) {
        // --- IMPORTANT: You need to extract actual values from your dialog's UI controls ---
        // For demonstration, using existing profile values. You'd get these from dialogView.
        int newBrightness = profile.getPreferredBrightness(); // Replace with actual value from dialog
        String newColor = profile.getPreferredColor();       // Replace with actual value from dialog
        String newSunsetTime = profile.getPreferredSunsetTime(); // Replace with actual value from dialog
        String newBedtime = profile.getPreferredBedtime();     // Replace with actual value from dialog
        boolean newAutoSchedule = profile.isAutoScheduleEnabled(); // Replace with actual value from dialog

        // Example of extracting from a hypothetical TextInputEditText:
        // TextInputEditText editBrightness = dialogView.findViewById(R.id.editBrightness);
        // try { newBrightness = Integer.parseInt(editBrightness.getText().toString()); } catch (NumberFormatException e) {/* handle error */}

        speakerService.updateSpeakerPreferences(
                profile.getProfileId(),
                newBrightness,
                newColor,
                newSunsetTime,
                newBedtime,
                newAutoSchedule
        );

        refreshSpeakerList();
        safeShowSnackbar("Preferences updated for " + profile.getUserName());
    }

    /**
     * Confirms and then initiates deletion of a speaker profile.
     * @param profile The SpeakerProfile to delete.
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
     * Deletes a speaker profile from Azure and local storage.
     * @param profile The SpeakerProfile to delete.
     */
    private void deleteSpeaker(@NonNull SpeakerProfile profile) {
        isProcessingApiCall = true;
        safeShowSnackbar("Deleting profile for " + profile.getUserName() + "...");
        speakerService.deleteSpeakerProfile(profile.getProfileId(),
                new AzureSpeakerIdentificationService.DeleteProfileCallback() {
                    @Override
                    public void onProfileDeleted(String profileId) {
                        isProcessingApiCall = false;
                        safeUpdateUI(() -> {
                            refreshSpeakerList(); // Update UI list
                            if (currentSpeaker != null && currentSpeaker.getProfileId().equals(profileId)) {
                                currentSpeaker = null; // Clear current speaker if it was the one deleted
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
                            updateControlStates(); // Re-enable buttons
                        });
                    }
                });
    }

    /**
     * Placeholder for future voice command processing.
     */
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
    private void updateControlStates() {
        boolean isAudioPermissionGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean canControl = tuyaService != null && tuyaService.isConnected() && isAudioPermissionGranted;

        // Disable interaction if recording or an API call is in progress
        buttonIdentify.setEnabled(canControl && !isRecording && !isProcessingApiCall);
        buttonEnrollNew.setEnabled(canControl && !isRecording && !isProcessingApiCall);
        fabVoiceCommand.setEnabled(canControl && !isRecording && !isProcessingApiCall);

        // Ensure RecyclerView items are also disabled if recording/processing
        if (speakerAdapter != null) {
            speakerAdapter.setInteractionEnabled(!isRecording && !isProcessingApiCall);
        }
    }

    /**
     * Shows/hides the recording status UI.
     * @param recording True to show, false to hide.
     * @param statusText The text to display.
     */
    private void updateRecordingUI(boolean recording, String statusText) {
        if (recordingStatusContainer != null) {
            progressRecording.setVisibility(recording ? View.VISIBLE : View.GONE);
            textRecordingStatus.setText(statusText);
            recordingStatusContainer.setVisibility(recording ? View.VISIBLE : View.GONE); // Show/hide the entire container
        }
        updateControlStates(); // Re-evaluate button states
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
            // Optionally display a default message when no speaker is identified
            // textCurrentSpeaker.setText("No Speaker Identified");
            // textSpeakerPreferences.setText("Perform identification to apply preferences.");
        }
    }

    /**
     * Refreshes the list of enrolled speakers displayed in the RecyclerView.
     */
    private void refreshSpeakerList() {
        List<SpeakerProfile> speakers = speakerService.getEnrolledSpeakers();
        speakerAdapter.updateSpeakers(speakers);

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
        }
    }

    /**
     * Helper to get a human-readable name for a hex color.
     */
    private String getColorName(String hexColor) {
        // This mapping should ideally be consistent with your LightSettings or a central color utility
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
            default: return hexColor; // Return hex if no name found
        }
    }

    // --- Safe UI Update Helpers (ensures updates happen on UI thread and fragment is active) ---
    private void safeUpdateUI(Runnable updateTask) {
        if (isAdded() && getActivity() != null) {
            getActivity().runOnUiThread(updateTask);
        }
    }

    private void safeShowSnackbar(String message) {
        safeUpdateUI(() -> {
            if (rootView != null) {
                Snackbar.make(rootView, message, Snackbar.LENGTH_LONG).show(); // Changed to LONG for better visibility
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
        // Reconnect Tuya if needed, and refresh UI state
        if (tuyaService != null && !tuyaService.isConnected()) {
            tuyaService.connect();
        }
        updateUI(); // Ensure UI reflects current state (e.g., after returning from settings)
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "VoiceControlFragment onPause");
        // Ensure any ongoing recording is stopped to prevent resource leaks
        if (isRecording) {
            // It's generally better to stop recording in the service itself,
            // but as a failsafe, ensure the flag is reset here if needed.
            // speakerService.stopRecording(); // Assuming you add a public stop method to your service
            isRecording = false;
            isProcessingApiCall = false;
            updateRecordingUI(false, "");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "VoiceControlFragment onDestroyView");
        // Disconnect Tuya service to release resources
        if (tuyaService != null) {
            tuyaService.disconnect();
        }
        // Ensure audio recording is stopped if still active
        // You might need a public method in AzureSpeakerIdentificationService to force stop all operations.
    }

    /**
     * Speaker Adapter for RecyclerView
     */
    private static class SpeakerAdapter extends RecyclerView.Adapter<SpeakerAdapter.SpeakerViewHolder> {

        public interface SpeakerActionListener {
            void onSpeakerSelected(SpeakerProfile profile);
            void onEditPreferences(SpeakerProfile profile);
            void onDeleteSpeaker(SpeakerProfile profile);
            void onEnrollMore(SpeakerProfile profile); // New action for incomplete enrollments
        }

        private List<SpeakerProfile> speakers;
        private SpeakerActionListener listener;
        private boolean interactionEnabled = true; // New flag to control item clickability

        public SpeakerAdapter(List<SpeakerProfile> speakers, SpeakerActionListener listener) {
            this.speakers = speakers;
            this.listener = listener;
        }

        public void updateSpeakers(List<SpeakerProfile> speakers) {
            this.speakers = speakers;
            notifyDataSetChanged(); // Notify adapter that data has changed
        }

        public void setInteractionEnabled(boolean enabled) {
            if (this.interactionEnabled != enabled) {
                this.interactionEnabled = enabled;
                notifyDataSetChanged(); // Rebind views to update clickable state
            }
        }

        @NonNull
        @Override
        public SpeakerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_speaker_profile, parent, false); // Make sure this layout exists
            return new SpeakerViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SpeakerViewHolder holder, int position) {
            SpeakerProfile profile = speakers.get(position);
            holder.bind(profile, listener, interactionEnabled); // Pass interaction state
        }

        @Override
        public int getItemCount() {
            return speakers.size();
        }

        static class SpeakerViewHolder extends RecyclerView.ViewHolder {
            private TextView textName;
            private TextView textLastSeen;
            private TextView textEnrollmentStatus; // New TextView for enrollment status
            private View colorIndicator;
            private Button buttonApply;
            private Button buttonEdit;
            private Button buttonDelete;
            private Button buttonEnrollMore; // New button for "Enroll More"

            public SpeakerViewHolder(@NonNull View itemView) {
                super(itemView);
                textName = itemView.findViewById(R.id.textSpeakerName);
                textLastSeen = itemView.findViewById(R.id.textLastSeen);
                textEnrollmentStatus = itemView.findViewById(R.id.textEnrollmentStatus); // Make sure this ID exists
                colorIndicator = itemView.findViewById(R.id.colorIndicator);
                buttonApply = itemView.findViewById(R.id.buttonApply);
                buttonEdit = itemView.findViewById(R.id.buttonEdit);
                buttonDelete = itemView.findViewById(R.id.buttonDelete);
                buttonEnrollMore = itemView.findViewById(R.id.buttonEnrollMore); // Make sure this ID exists
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
                    textLastSeen.setVisibility(View.GONE); // Hide if never identified
                }


                // Display enrollment status
                String status = profile.getEnrollmentStatus();
                textEnrollmentStatus.setText("Status: " + status);
                if ("Enrolled".equalsIgnoreCase(status)) {
                    textEnrollmentStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_green_dark));
                    buttonApply.setVisibility(View.VISIBLE);
                    buttonEnrollMore.setVisibility(View.GONE); // Hide enroll more if already enrolled
                } else {
                    textEnrollmentStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.holo_orange_dark));
                    textEnrollmentStatus.append(" (" + profile.getRemainingEnrollmentsSpeechLength() + "s needed)");
                    buttonApply.setVisibility(View.GONE); // Cannot apply preferences if not fully enrolled
                    buttonEnrollMore.setVisibility(View.VISIBLE); // Show enroll more
                }


                // Set color indicator
                try {
                    colorIndicator.setBackgroundColor(Color.parseColor(profile.getPreferredColor()));
                } catch (Exception e) {
                    Log.e("SpeakerViewHolder", "Invalid color hex: " + profile.getPreferredColor(), e);
                    colorIndicator.setBackgroundColor(Color.WHITE); // Default to white on error
                }

                // Set button listeners and manage enable state
                buttonApply.setOnClickListener(v -> listener.onSpeakerSelected(profile));
                buttonEdit.setOnClickListener(v -> listener.onEditPreferences(profile));
                buttonDelete.setOnClickListener(v -> listener.onDeleteSpeaker(profile));
                buttonEnrollMore.setOnClickListener(v -> listener.onEnrollMore(profile));

                // Control button and item clickability based on fragment's overall interaction state
                itemView.setEnabled(interactionEnabled);
                buttonApply.setEnabled(interactionEnabled && "Enrolled".equalsIgnoreCase(status)); // Can only apply if enrolled
                buttonEdit.setEnabled(interactionEnabled);
                buttonDelete.setEnabled(interactionEnabled);
                buttonEnrollMore.setEnabled(interactionEnabled && !"Enrolled".equalsIgnoreCase(status)); // Can only enroll more if not yet enrolled
            }
        }
    }
}