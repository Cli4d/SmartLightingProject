package com.smart.smartbulb.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Azure Speaker Identification Service for Smart Bulb App
 * Updated to use the current Azure Speaker Recognition API (2021-09-05)
 * Identifies speakers and applies their lighting preferences
 */
public class AzureSpeakerIdentificationService {
    private static final String TAG = "AzureSpeakerID";

    // Azure Configuration - UPDATED TO CURRENT API
    private static final String AZURE_ENDPOINT = "https://westeurope.api.cognitive.microsoft.com/";
    // !!! WARNING: This key is publicly visible. For production apps, use a secure way to store and retrieve keys.
    private static final String SUBSCRIPTION_KEY = "9nVwK60ioyhw51bRg7LfTRMuSevlmPmxcclo8Ke5P4gO1bsh1xLcJQQJ99BEAC5RqLJXJ3w3AAAYACOGK5kk";

    // API Version and Endpoints - CORRECTED FOR 2021-09-05 API
    private static final String API_VERSION = "2021-09-05";

    // Profile management endpoints for IDENTIFICATION (text-independent)
    private static final String PROFILE_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles?api-version=" + API_VERSION;
    private static final String ENROLLMENT_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles/%s/enrollments?api-version=" + API_VERSION;
    private static final String IDENTIFY_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles:identifySingleSpeaker?api-version=" + API_VERSION;
    private static final String DELETE_PROFILE_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles/%s?api-version=" + API_VERSION;
    private static final String GET_PROFILE_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles/%s?api-version=" + API_VERSION;
    private static final String LIST_PROFILES_ENDPOINT = AZURE_ENDPOINT + "speaker-recognition/identification/text-independent/profiles?api-version=" + API_VERSION;

    // Audio Recording Configuration
    private static final int SAMPLE_RATE = 16000; // Hz
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

    // Recording state variables
    private AudioRecord audioRecord;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private ByteArrayOutputStream audioBuffer;
    private Thread recordingThread;

    // Speaker profiles storage (in-memory cache)
    private Map<String, SpeakerProfile> speakerProfiles;
    private Context context;

    // --- Callback Interfaces ---
    public interface SpeakerIdentificationCallback {
        void onSpeakerIdentified(SpeakerProfile profile);
        void onIdentificationFailed(String error);
        void onError(String error);
    }

    public interface CreateProfileCallback {
        void onProfileCreated(String profileId);
        void onProfileCreated(String profileId, String userName);
        void onError(String error);
    }

    public interface EnrollmentCallback {
        void onRecordingStarted();
        void onEnrollmentComplete(String profileId, String userName);
        void onEnrollmentComplete(String profileId);
        void onEnrollmentProgress(int remainingSeconds);
        void onError(String error);
    }

    public interface DeleteProfileCallback {
        void onProfileDeleted(String profileId);
        void onError(String error);
    }

    public interface ProfileStatusCallback {
        void onProfileStatus(SpeakerProfile profile);
        void onError(String error);
    }

    /**
     * Speaker Profile with lighting preferences and other metadata.
     */
    public static class SpeakerProfile {
        private String profileId;
        private String userName;
        private int preferredBrightness;
        private String preferredColor;
        private String preferredSunsetTime;
        private String preferredBedtime;
        private boolean autoScheduleEnabled;
        private long lastIdentified;
        private String enrollmentStatus;
        private int remainingEnrollmentsSpeechLength;

        public SpeakerProfile(String profileId, String userName) {
            this.profileId = profileId;
            this.userName = userName;
            // Default preferences
            this.preferredBrightness = 80;
            this.preferredColor = "#FFDD99";
            this.preferredSunsetTime = "18:30";
            this.preferredBedtime = "22:00";
            this.autoScheduleEnabled = true;
            this.lastIdentified = 0;
            this.enrollmentStatus = "Enrolling";
            this.remainingEnrollmentsSpeechLength = 20;
            Log.d(TAG, "New SpeakerProfile created for user: " + userName + ", ID: " + profileId);
        }

        // --- Getters ---
        public String getProfileId() { return profileId; }
        public String getUserName() { return userName; }
        public int getPreferredBrightness() { return preferredBrightness; }
        public String getPreferredColor() { return preferredColor; }
        public String getPreferredSunsetTime() { return preferredSunsetTime; }
        public String getPreferredBedtime() { return preferredBedtime; }
        public boolean isAutoScheduleEnabled() { return autoScheduleEnabled; }
        public long getLastIdentified() { return lastIdentified; }
        public String getEnrollmentStatus() { return enrollmentStatus; }
        public int getRemainingEnrollmentsSpeechLength() { return remainingEnrollmentsSpeechLength; }

        // --- Setters ---
        public void setPreferredBrightness(int brightness) {
            this.preferredBrightness = brightness;
            Log.d(TAG, "Setting brightness for " + userName + " to " + brightness);
        }
        public void setPreferredColor(String color) {
            this.preferredColor = color;
            Log.d(TAG, "Setting color for " + userName + " to " + color);
        }
        public void setPreferredSunsetTime(String time) {
            this.preferredSunsetTime = time;
            Log.d(TAG, "Setting sunset time for " + userName + " to " + time);
        }
        public void setPreferredBedtime(String time) {
            this.preferredBedtime = time;
            Log.d(TAG, "Setting bedtime for " + userName + " to " + time);
        }
        public void setAutoScheduleEnabled(boolean enabled) {
            this.autoScheduleEnabled = enabled;
            Log.d(TAG, "Setting auto schedule for " + userName + " to " + enabled);
        }
        public void updateLastIdentified() {
            this.lastIdentified = System.currentTimeMillis();
            Log.d(TAG, "Updating last identified time for " + userName);
        }
        public void setEnrollmentStatus(String status) {
            this.enrollmentStatus = status;
        }
        public void setRemainingEnrollmentsSpeechLength(int length) {
            this.remainingEnrollmentsSpeechLength = length;
        }

        /**
         * Converts SpeakerProfile object to a JSON object for storage.
         */
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("profileId", profileId);
            json.put("userName", userName);
            json.put("preferredBrightness", preferredBrightness);
            json.put("preferredColor", preferredColor);
            json.put("preferredSunsetTime", preferredSunsetTime);
            json.put("preferredBedtime", preferredBedtime);
            json.put("autoScheduleEnabled", autoScheduleEnabled);
            json.put("lastIdentified", lastIdentified);
            json.put("enrollmentStatus", enrollmentStatus);
            json.put("remainingEnrollmentsSpeechLength", remainingEnrollmentsSpeechLength);
            Log.d(TAG, "Converting SpeakerProfile to JSON for " + userName);
            return json;
        }

        /**
         * Creates a SpeakerProfile object from a JSON object loaded from storage.
         */
        public static SpeakerProfile fromJson(JSONObject json) throws JSONException {
            SpeakerProfile profile = new SpeakerProfile(
                    json.getString("profileId"),
                    json.getString("userName")
            );
            profile.setPreferredBrightness(json.getInt("preferredBrightness"));
            profile.setPreferredColor(json.getString("preferredColor"));
            profile.setPreferredSunsetTime(json.getString("preferredSunsetTime"));
            profile.setPreferredBedtime(json.getString("preferredBedtime"));
            profile.setAutoScheduleEnabled(json.getBoolean("autoScheduleEnabled"));
            profile.lastIdentified = json.optLong("lastIdentified", 0);
            profile.enrollmentStatus = json.optString("enrollmentStatus", "Enrolling");
            profile.remainingEnrollmentsSpeechLength = json.optInt("remainingEnrollmentsSpeechLength", 20);
            Log.d(TAG, "Creating SpeakerProfile from JSON for user: " + profile.getUserName());
            return profile;
        }
    }

    public AzureSpeakerIdentificationService(Context context) {
        this.context = context;
        this.speakerProfiles = new HashMap<>();
        loadSpeakerProfiles();
        Log.i(TAG, "AzureSpeakerIdentificationService initialized with corrected API endpoints.");
    }

    /**
     * Creates a new speaker profile in Azure and locally.
     */
    public void createSpeakerProfile(String userName, CreateProfileCallback callback) {
        Log.d(TAG, "Attempting to create speaker profile for user: " + userName);

        // Prevent duplicate profiles for the same user name in local storage
        for (SpeakerProfile profile : speakerProfiles.values()) {
            if (profile.getUserName().equalsIgnoreCase(userName)) {
                Log.w(TAG, "Profile already exists for user: " + userName + ". ID: " + profile.getProfileId());
                callback.onError("A profile for '" + userName + "' already exists.");
                return;
            }
        }
        new CreateProfileTask(userName, callback).execute();
    }

    /**
     * Enrolls a speaker with an audio sample.
     */
    public void enrollSpeaker(String profileId, EnrollmentCallback callback) {
        Log.d(TAG, "Attempting to enroll speaker with profile ID: " + profileId);
        if (isRecording.get()) {
            Log.w(TAG, "Enrollment failed: Already recording.");
            callback.onError("Already recording.");
            return;
        }

        SpeakerProfile profileToEnroll = speakerProfiles.get(profileId);
        if (profileToEnroll == null) {
            Log.e(TAG, "Enrollment failed: Profile ID " + profileId + " not found locally.");
            callback.onError("Profile not found for enrollment.");
            return;
        }

        startRecording();
        Log.d(TAG, "Recording started for enrollment of profile ID: " + profileId);
        callback.onRecordingStarted();

        // Record for 10 seconds for each enrollment chunk
        int recordingDurationMillis = 10000;

        new android.os.Handler().postDelayed(() -> {
            Log.d(TAG, "Enrollment recording duration (" + (recordingDurationMillis / 1000) + "s) reached. Stopping recording...");
            byte[] audioData = stopRecording();
            if (audioData != null && audioData.length > 44) {
                Log.d(TAG, "Audio data captured for enrollment. Size: " + audioData.length + " bytes.");
                new EnrollSpeakerTask(profileId, audioData, callback, profileToEnroll).execute();
            } else {
                String errorMessage = "Failed to record audio or audio too short for enrollment.";
                Log.e(TAG, errorMessage);
                callback.onError(errorMessage);
            }
        }, recordingDurationMillis);
    }

    /**
     * Identifies a speaker from an audio sample against all enrolled profiles.
     */
    public void identifySpeaker(SpeakerIdentificationCallback callback) {
        Log.d(TAG, "Attempting to identify speaker.");
        if (isRecording.get()) {
            Log.w(TAG, "Identification failed: Already recording.");
            callback.onError("Already recording.");
            return;
        }

        if (speakerProfiles.isEmpty()) {
            Log.w(TAG, "Identification failed: No enrolled speakers found.");
            callback.onError("No enrolled speakers. Please create and enroll a profile first.");
            return;
        }

        // Filter out profiles that are not yet 'Enrolled'
        List<String> enrolledProfileIds = new ArrayList<>();
        for (SpeakerProfile profile : speakerProfiles.values()) {
            if ("Enrolled".equalsIgnoreCase(profile.getEnrollmentStatus())) {
                enrolledProfileIds.add(profile.getProfileId());
            } else {
                Log.d(TAG, "Skipping profile " + profile.getUserName() + " (" + profile.getProfileId() + ") as enrollment status is: " + profile.getEnrollmentStatus());
            }
        }

        if (enrolledProfileIds.isEmpty()) {
            Log.w(TAG, "Identification failed: No fully enrolled speakers found.");
            callback.onError("No fully enrolled speakers available for identification.");
            return;
        }

        startRecording();
        Log.d(TAG, "Recording started for speaker identification.");

        // Record for 4 seconds for identification
        new android.os.Handler().postDelayed(() -> {
            Log.d(TAG, "Identification recording duration reached. Stopping recording...");
            byte[] audioData = stopRecording();
            if (audioData != null && audioData.length > 44) {
                Log.d(TAG, "Audio data captured for identification. Size: " + audioData.length + " bytes.");
                new IdentifySpeakerTask(audioData, enrolledProfileIds, callback).execute();
            } else {
                String errorMessage = "Failed to record audio or audio too short for identification.";
                Log.e(TAG, errorMessage);
                callback.onError(errorMessage);
            }
        }, 4000);
    }

    /**
     * Updates local speaker preferences for an existing profile.
     */
    public void updateSpeakerPreferences(String profileId, int brightness, String color,
                                         String sunsetTime, String bedtime, boolean autoSchedule) {
        Log.d(TAG, "Attempting to update preferences for profile ID: " + profileId);
        SpeakerProfile profile = speakerProfiles.get(profileId);
        if (profile != null) {
            profile.setPreferredBrightness(brightness);
            profile.setPreferredColor(color);
            profile.setPreferredSunsetTime(sunsetTime);
            profile.setPreferredBedtime(bedtime);
            profile.setAutoScheduleEnabled(autoSchedule);
            saveSpeakerProfiles();
            Log.i(TAG, "Speaker preferences updated and saved for profile ID: " + profileId);
        } else {
            Log.w(TAG, "Failed to update preferences: Profile ID " + profileId + " not found.");
        }
    }

    /**
     * Retrieves all enrolled speaker profiles currently loaded.
     */
    public List<SpeakerProfile> getEnrolledSpeakers() {
        Log.d(TAG, "Retrieving all enrolled speakers. Found " + speakerProfiles.size() + " profiles.");
        return new ArrayList<>(speakerProfiles.values());
    }

    /**
     * Deletes a speaker profile from Azure and local storage.
     */
    public void deleteSpeakerProfile(String profileId, DeleteProfileCallback callback) {
        Log.d(TAG, "Attempting to delete speaker profile with ID: " + profileId);
        new DeleteProfileTask(profileId, callback).execute();
    }

    /**
     * Gets the current status of a profile from Azure.
     */
    public void getProfileStatus(String profileId, ProfileStatusCallback callback) {
        Log.d(TAG, "Attempting to get status for profile ID: " + profileId);
        new GetProfileStatusTask(profileId, callback).execute();
    }

    /**
     * Clears all local profiles (useful for debugging or reset).
     */
    public void clearAllProfiles() {
        SharedPreferences sharedPrefs = context.getSharedPreferences("speaker_profiles", Context.MODE_PRIVATE);
        sharedPrefs.edit().clear().apply();
        speakerProfiles.clear();
        Log.i(TAG, "All local profiles cleared");
    }

    // --- Audio Recording Methods ---
    private void startRecording() {
        Log.d(TAG, "Initializing audio recording...");
        audioBuffer = new ByteArrayOutputStream();

        if (audioRecord != null) {
            audioRecord.release();
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed. State: " + audioRecord.getState() + ". Check permissions!");
            return;
        }

        isRecording.set(true);
        audioRecord.startRecording();
        Log.i(TAG, "Audio recording started successfully.");

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[BUFFER_SIZE];
            Log.d(TAG, "Audio recording thread started.");
            while (isRecording.get()) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    audioBuffer.write(buffer, 0, read);
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error: " + read);
                    isRecording.set(false);
                }
            }
            Log.d(TAG, "Audio recording thread stopped.");
        }, "AudioRecordingThread");
        recordingThread.start();
    }

    private byte[] stopRecording() {
        Log.d(TAG, "Attempting to stop audio recording.");
        if (!isRecording.get()) {
            Log.w(TAG, "Recording was not active, cannot stop.");
            return null;
        }

        isRecording.set(false);

        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "Recording thread interrupted during stop: " + e.getMessage());
            }
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping AudioRecord: " + e.getMessage());
            } finally {
                audioRecord.release();
                audioRecord = null;
            }
            Log.i(TAG, "Audio recording stopped and resources released.");
        }

        byte[] pcmData = audioBuffer.toByteArray();
        Log.d(TAG, "PCM audio data collected. Size: " + pcmData.length + " bytes.");

        if (pcmData.length > SAMPLE_RATE * 2) {
            return createWavFile(pcmData);
        } else {
            Log.w(TAG, "PCM data too small (" + pcmData.length + " bytes) for WAV conversion.");
            return null;
        }
    }

    /**
     * Creates a WAV file byte array from raw PCM data.
     */
    private byte[] createWavFile(byte[] pcmData) {
        Log.d(TAG, "Converting PCM data to WAV format.");
        int pcmSize = pcmData.length;
        int wavSize = pcmSize + 44;

        ByteBuffer buffer = ByteBuffer.allocate(wavSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put("RIFF".getBytes());
        buffer.putInt(wavSize - 8);
        buffer.put("WAVE".getBytes());

        // fmt chunk
        buffer.put("fmt ".getBytes());
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * (16 / 8) * 1);
        buffer.putShort((short) (1 * (16 / 8)));
        buffer.putShort((short) 16);

        // data chunk
        buffer.put("data".getBytes());
        buffer.putInt(pcmSize);
        buffer.put(pcmData);

        Log.d(TAG, "WAV file created. Total size: " + buffer.array().length + " bytes.");
        return buffer.array();
    }

    // --- Local Storage Methods ---
    private void loadSpeakerProfiles() {
        Log.d(TAG, "Loading speaker profiles from SharedPreferences...");
        SharedPreferences sharedPrefs = context.getSharedPreferences("speaker_profiles", Context.MODE_PRIVATE);
        try {
            String jsonString = sharedPrefs.getString("profiles", "{}");
            JSONObject profilesJson = new JSONObject(jsonString);

            speakerProfiles.clear();

            for (Iterator<String> it = profilesJson.keys(); it.hasNext(); ) {
                String key = it.next();
                try {
                    SpeakerProfile profile = SpeakerProfile.fromJson(profilesJson.getJSONObject(key));
                    speakerProfiles.put(key, profile);
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing individual profile from JSON for key " + key + ": " + e.getMessage(), e);
                }
            }

            Log.i(TAG, "Successfully loaded " + speakerProfiles.size() + " speaker profiles.");
        } catch (JSONException e) {
            Log.e(TAG, "Error loading speaker profiles from SharedPreferences: " + e.getMessage(), e);
        }
    }

    private void saveSpeakerProfiles() {
        Log.d(TAG, "Saving speaker profiles to SharedPreferences...");
        SharedPreferences sharedPrefs = context.getSharedPreferences("speaker_profiles", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPrefs.edit();
        try {
            JSONObject profilesJson = new JSONObject();
            for (Map.Entry<String, SpeakerProfile> entry : speakerProfiles.entrySet()) {
                profilesJson.put(entry.getKey(), entry.getValue().toJson());
            }

            editor.putString("profiles", profilesJson.toString());
            editor.apply();

            Log.i(TAG, "Successfully saved " + speakerProfiles.size() + " speaker profiles.");
        } catch (JSONException e) {
            Log.e(TAG, "Error saving speaker profiles to SharedPreferences: " + e.getMessage(), e);
        }
    }

    // --- AsyncTask Classes for API Interactions ---

    private class CreateProfileTask extends AsyncTask<Void, Void, String> {
        private String userName;
        private CreateProfileCallback callback;
        private String error;

        public CreateProfileTask(String userName, CreateProfileCallback callback) {
            this.userName = userName;
            this.callback = callback;
            Log.d(TAG, "CreateProfileTask created for user: " + userName);
        }

        @Override
        protected String doInBackground(Void... voids) {
            Log.d(TAG, "Executing CreateProfileTask for user: " + userName);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(PROFILE_ENDPOINT);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                Log.d(TAG, "HTTP POST request setup for profile creation. URL: " + url.toString());

                JSONObject body = new JSONObject();
                body.put("locale", "en-US");

                DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                os.writeBytes(body.toString());
                os.flush();
                os.close();
                Log.d(TAG, "Request body sent: " + body.toString());

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "CreateProfileTask HTTP response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    Log.d(TAG, "CreateProfileTask API response: " + response.toString());

                    JSONObject result = new JSONObject(response.toString());
                    String profileId = result.getString("profileId");

                    // Create and save local profile
                    SpeakerProfile profile = new SpeakerProfile(profileId, userName);
                    profile.setEnrollmentStatus(result.optString("enrollmentStatus", "Enrolling"));
                    profile.setRemainingEnrollmentsSpeechLength((int)result.optDouble("remainingEnrollmentsSpeechLengthInSec", 20.0));

                    speakerProfiles.put(profileId, profile);
                    saveSpeakerProfiles();
                    Log.i(TAG, "New SpeakerProfile created for user: " + userName + ", ID: " + profileId);
                    return profileId;
                } else {
                    error = "Failed to create profile: HTTP " + responseCode + " - " + conn.getResponseMessage();
                    Log.e(TAG, error);

                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        String errorLine;
                        StringBuilder errorResponse = new StringBuilder();
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        Log.e(TAG, "Error stream: " + errorResponse.toString());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                error = "Error creating profile: " + e.getMessage();
                Log.e(TAG, error, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                    Log.d(TAG, "HTTP connection for CreateProfileTask disconnected.");
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(String profileId) {
            if (profileId != null) {
                callback.onProfileCreated(profileId, userName);
            } else {
                callback.onError(error != null ? error : "Unknown error during profile creation");
            }
            Log.d(TAG, "CreateProfileTask finished. Profile ID: " + profileId + ", Error: " + error);
        }
    }

    private class EnrollSpeakerTask extends AsyncTask<Void, Void, Boolean> {
        private String profileId;
        private byte[] audioData;
        private EnrollmentCallback callback;
        private String error;
        private SpeakerProfile localProfile;

        public EnrollSpeakerTask(String profileId, byte[] audioData, EnrollmentCallback callback, SpeakerProfile localProfile) {
            this.profileId = profileId;
            this.audioData = audioData;
            this.callback = callback;
            this.localProfile = localProfile;
            Log.d(TAG, "EnrollSpeakerTask created for profile ID: " + profileId + " with audio data size: " + audioData.length);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            Log.d(TAG, "Executing EnrollSpeakerTask for profile ID: " + profileId);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(String.format(ENROLLMENT_ENDPOINT, profileId));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                conn.setRequestProperty("Content-Type", "audio/wav");
                conn.setDoOutput(true);
                Log.d(TAG, "HTTP POST request setup for enrollment. URL: " + url.toString());

                DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                os.write(audioData);
                os.flush();
                os.close();
                Log.d(TAG, "Audio data sent for enrollment.");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "EnrollSpeakerTask HTTP response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    Log.d(TAG, "EnrollSpeakerTask API response: " + response.toString());

                    JSONObject result = new JSONObject(response.toString());
                    String status = result.getString("enrollmentStatus");
                    int remainingSpeechLength = (int) result.optDouble("remainingEnrollmentsSpeechLengthInSec", 0.0);

                    Log.d(TAG, "Enrollment status received: " + status + ", Remaining speech: " + remainingSpeechLength + " seconds.");

                    // Update local profile
                    if (localProfile != null) {
                        localProfile.setEnrollmentStatus(status);
                        localProfile.setRemainingEnrollmentsSpeechLength(remainingSpeechLength);
                        saveSpeakerProfiles();
                    }

                    if ("Enrolled".equalsIgnoreCase(status)) {
                        Log.i(TAG, "Speaker successfully enrolled for profile ID: " + profileId);
                        return true;
                    } else {
                        callback.onEnrollmentProgress(remainingSpeechLength);
                        return false;
                    }
                } else {
                    error = "Enrollment failed: HTTP " + responseCode + " - " + conn.getResponseMessage();
                    Log.e(TAG, error);

                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        String errorLine;
                        StringBuilder errorResponse = new StringBuilder();
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        Log.e(TAG, "Error stream: " + errorResponse.toString());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                error = "Error during enrollment: " + e.getMessage();
                Log.e(TAG, error, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                    Log.d(TAG, "HTTP connection for EnrollSpeakerTask disconnected.");
                }
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                callback.onEnrollmentComplete(profileId, localProfile != null ? localProfile.getUserName() : "Unknown User");
            } else if (error != null) {
                callback.onError(error);
            } else {
                callback.onError("Enrollment not complete, please provide more speech.");
            }
            Log.d(TAG, "EnrollSpeakerTask finished. Success: " + success + ", Error: " + error);
        }
    }

    private class IdentifySpeakerTask extends AsyncTask<Void, Void, SpeakerProfile> {
        private byte[] audioData;
        private List<String> profileIdsToIdentify;
        private SpeakerIdentificationCallback callback;
        private String error;

        public IdentifySpeakerTask(byte[] audioData, List<String> profileIdsToIdentify, SpeakerIdentificationCallback callback) {
            this.audioData = audioData;
            this.profileIdsToIdentify = profileIdsToIdentify;
            this.callback = callback;
            Log.d(TAG, "IdentifySpeakerTask created with audio data size: " + audioData.length);
        }

        @Override
        protected SpeakerProfile doInBackground(Void... voids) {
            Log.d(TAG, "Executing IdentifySpeakerTask.");
            HttpURLConnection conn = null;
            try {
                // Build URL with profileIds as query parameter
                String profileIdsParam = String.join(",", profileIdsToIdentify);
                String encodedProfileIdsParam = URLEncoder.encode(profileIdsParam, "UTF-8");
                URL url = new URL(IDENTIFY_ENDPOINT + "&profileIds=" + encodedProfileIdsParam);
                Log.d(TAG, "Identification URL: " + url.toString());

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                conn.setRequestProperty("Content-Type", "audio/wav");
                conn.setDoOutput(true);
                Log.d(TAG, "HTTP POST request setup for identification.");

                DataOutputStream os = new DataOutputStream(conn.getOutputStream());
                os.write(audioData);
                os.flush();
                os.close();
                Log.d(TAG, "Audio data sent for identification.");

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "IdentifySpeakerTask HTTP response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    Log.d(TAG, "IdentifySpeakerTask API response: " + response.toString());

                    JSONObject result = new JSONObject(response.toString());

                    // Parse the new API response format
                    JSONObject identifiedProfile = result.optJSONObject("identifiedProfile");
                    if (identifiedProfile != null) {
                        String identifiedProfileId = identifiedProfile.optString("profileId", null);
                        double confidence = identifiedProfile.optDouble("score", 0.0);
                        Log.d(TAG, "Identified Profile ID: " + identifiedProfileId + ", Confidence: " + confidence);

                        // Check confidence threshold (Azure recommends > 0.5)
                        if (identifiedProfileId != null && confidence > 0.5) {
                            SpeakerProfile profile = speakerProfiles.get(identifiedProfileId);
                            if (profile != null) {
                                profile.updateLastIdentified();
                                saveSpeakerProfiles();
                                Log.i(TAG, "Speaker identified: " + profile.getUserName() + " with profile ID: " + identifiedProfileId);
                                return profile;
                            } else {
                                Log.w(TAG, "Identified profile ID " + identifiedProfileId + " not found in local storage.");
                                error = "Identified profile not found in local storage.";
                            }
                        } else {
                            Log.i(TAG, "Speaker not identified with sufficient confidence or no profile found.");
                            error = "Could not identify speaker with sufficient confidence or no match found.";
                        }
                    } else {
                        Log.i(TAG, "No identified profile in response.");
                        error = "No speaker identified from the audio sample.";
                    }
                } else {
                    error = "Identification failed: HTTP " + responseCode + " - " + conn.getResponseMessage();
                    Log.e(TAG, error);

                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        String errorLine;
                        StringBuilder errorResponse = new StringBuilder();
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        Log.e(TAG, "Error stream: " + errorResponse.toString());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    }
                }

            } catch (Exception e) {
                error = "Error during identification: " + e.getMessage();
                Log.e(TAG, error, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                    Log.d(TAG, "HTTP connection for IdentifySpeakerTask disconnected.");
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(SpeakerProfile profile) {
            if (profile != null) {
                callback.onSpeakerIdentified(profile);
            } else {
                callback.onIdentificationFailed(error != null ? error : "Unknown speaker or identification failed");
            }
            Log.d(TAG, "IdentifySpeakerTask finished. Profile: " + (profile != null ? profile.getUserName() : "null") + ", Error: " + error);
        }
    }

    private class DeleteProfileTask extends AsyncTask<Void, Void, Boolean> {
        private String profileId;
        private DeleteProfileCallback callback;
        private String error;

        public DeleteProfileTask(String profileId, DeleteProfileCallback callback) {
            this.profileId = profileId;
            this.callback = callback;
            Log.d(TAG, "DeleteProfileTask created for profile ID: " + profileId);
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            Log.d(TAG, "Executing DeleteProfileTask for profile ID: " + profileId);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(String.format(DELETE_PROFILE_ENDPOINT, profileId));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                Log.d(TAG, "HTTP DELETE request setup for profile deletion. URL: " + url.toString());

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "DeleteProfileTask HTTP response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                    speakerProfiles.remove(profileId);
                    saveSpeakerProfiles();
                    Log.i(TAG, "Profile ID " + profileId + " deleted successfully from Azure and local storage.");
                    return true;
                } else {
                    error = "Failed to delete profile: HTTP " + responseCode + " - " + conn.getResponseMessage();
                    Log.e(TAG, error);

                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        String errorLine;
                        StringBuilder errorResponse = new StringBuilder();
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        Log.e(TAG, "Error stream: " + errorResponse.toString());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                error = "Error during profile deletion: " + e.getMessage();
                Log.e(TAG, error, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                    Log.d(TAG, "HTTP connection for DeleteProfileTask disconnected.");
                }
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (success) {
                callback.onProfileDeleted(profileId);
            } else {
                callback.onError(error != null ? error : "Unknown error during profile deletion");
            }
            Log.d(TAG, "DeleteProfileTask finished. Success: " + success + ", Error: " + error);
        }
    }

    private class GetProfileStatusTask extends AsyncTask<Void, Void, SpeakerProfile> {
        private String profileId;
        private ProfileStatusCallback callback;
        private String error;

        public GetProfileStatusTask(String profileId, ProfileStatusCallback callback) {
            this.profileId = profileId;
            this.callback = callback;
            Log.d(TAG, "GetProfileStatusTask created for profile ID: " + profileId);
        }

        @Override
        protected SpeakerProfile doInBackground(Void... voids) {
            Log.d(TAG, "Executing GetProfileStatusTask for profile ID: " + profileId);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(String.format(GET_PROFILE_ENDPOINT, profileId));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Ocp-Apim-Subscription-Key", SUBSCRIPTION_KEY);
                Log.d(TAG, "HTTP GET request setup for profile status. URL: " + url.toString());

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "GetProfileStatusTask HTTP response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    Log.d(TAG, "GetProfileStatusTask API response: " + response.toString());

                    JSONObject result = new JSONObject(response.toString());

                    // Update local profile with Azure status
                    SpeakerProfile localProfile = speakerProfiles.get(profileId);
                    if (localProfile != null) {
                        localProfile.setEnrollmentStatus(result.optString("enrollmentStatus", "Unknown"));
                        localProfile.setRemainingEnrollmentsSpeechLength(
                                (int) result.optDouble("remainingEnrollmentsSpeechLengthInSec", 0.0)
                        );
                        saveSpeakerProfiles();
                        Log.i(TAG, "Profile status updated for ID: " + profileId);
                        return localProfile;
                    } else {
                        error = "Profile not found in local storage.";
                        Log.w(TAG, error);
                    }
                } else {
                    error = "Failed to get profile status: HTTP " + responseCode + " - " + conn.getResponseMessage();
                    Log.e(TAG, error);

                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()))) {
                        String errorLine;
                        StringBuilder errorResponse = new StringBuilder();
                        while ((errorLine = errorReader.readLine()) != null) {
                            errorResponse.append(errorLine);
                        }
                        Log.e(TAG, "Error stream: " + errorResponse.toString());
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error stream: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                error = "Error getting profile status: " + e.getMessage();
                Log.e(TAG, error, e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                    Log.d(TAG, "HTTP connection for GetProfileStatusTask disconnected.");
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(SpeakerProfile profile) {
            if (profile != null) {
                callback.onProfileStatus(profile);
            } else {
                callback.onError(error != null ? error : "Unknown error getting profile status");
            }
            Log.d(TAG, "GetProfileStatusTask finished. Profile: " + (profile != null ? profile.getUserName() : "null") + ", Error: " + error);
        }
    }
}