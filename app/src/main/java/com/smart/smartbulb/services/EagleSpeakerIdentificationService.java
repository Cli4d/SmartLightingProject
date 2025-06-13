package com.smart.smartbulb.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.eagle.Eagle;
import ai.picovoice.eagle.EagleException;
import ai.picovoice.eagle.EagleProfile;
import ai.picovoice.eagle.EagleProfiler;
import ai.picovoice.eagle.EagleProfilerEnrollFeedback;
import ai.picovoice.eagle.EagleProfilerEnrollResult;

/**
 * Thread-Safe PicoVoice Eagle-based Speaker Identification Service
 * Fixed UI state and profile loading issues
 */
public class EagleSpeakerIdentificationService {
    private static final String TAG = "EagleSpeakerService";

    // Replace with your actual PicoVoice Access Key
    private static final String PICOVOICE_ACCESS_KEY = "CUHKkWMfH0maCy5FBzC9Rys0UWbXIoUa+14MrZtGvz0aKbPx+6AumA==";

    // Persistent storage constants
    private static final String PREFS_NAME = "eagle_speaker_profiles";
    private static final String PREFS_KEY_PROFILES = "enrolled_profiles";

    private final Context context;
    private final VoiceProcessor voiceProcessor;
    private final List<SpeakerProfile> enrolledSpeakers;
    private final SharedPreferences preferences;
    private final Gson gson;

    // Threading
    private final ExecutorService backgroundExecutor;
    private final Handler mainHandler;
    private final Handler backgroundHandler;
    private final HandlerThread backgroundThread;

    // Eagle components - only access from background thread
    private EagleProfiler eagleProfiler;
    private Eagle eagle;

    // State management with proper synchronization
    private volatile boolean isRecording = false;
    private volatile boolean isEnrolling = false;
    private volatile boolean isIdentifying = false;
    private volatile boolean isInitialized = false;
    private volatile boolean isInitializing = false;

    // Initialization callback for UI updates
    private InitializationCallback initializationCallback;

    // Enrollment tracking
    private ArrayList<Short> enrollmentBuffer;
    private int minEnrollSamples;
    private SpeakerProfile currentEnrollmentProfile;
    private long enrollmentStartTime;
    private int totalEnrollmentFrames;
    private static final int MIN_ENROLLMENT_DURATION_MS = 15000;
    private static final int MAX_ENROLLMENT_DURATION_MS = 60000;

    // Current enrollment callback
    private EnrollmentCallback currentEnrollmentCallback;

    // Identification tracking
    private float[] smoothScores;
    private int identificationFrameCount = 0;
    private static final int MIN_IDENTIFICATION_FRAMES = 50;

    public EagleSpeakerIdentificationService(Context context) {
        this.context = context;
        this.voiceProcessor = VoiceProcessor.getInstance();
        this.enrolledSpeakers = new ArrayList<>();
        this.enrollmentBuffer = new ArrayList<>();
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();

        // Initialize threading
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.backgroundExecutor = Executors.newSingleThreadExecutor();
        this.backgroundThread = new HandlerThread("EagleBackgroundThread");
        this.backgroundThread.start();
        this.backgroundHandler = new Handler(this.backgroundThread.getLooper());

        // Start initialization process
        initializeAsync();
    }

    /**
     * Async initialization with proper callback handling
     */
    private void initializeAsync() {
        isInitializing = true;
        backgroundExecutor.execute(() -> {
            try {
                Log.d(TAG, "Starting async initialization...");

                // Load stored profiles first (quick operation)
                loadStoredProfiles();

                // Initialize Eagle services
                initializeEagleProfiler();

                // Notify UI on main thread
                mainHandler.post(() -> {
                    isInitializing = false;
                    Log.d(TAG, "Initialization complete. Eagle initialized: " + isInitialized);

                    if (initializationCallback != null) {
                        initializationCallback.onInitializationComplete(isInitialized);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error during initialization", e);
                mainHandler.post(() -> {
                    isInitializing = false;
                    isInitialized = false;

                    if (initializationCallback != null) {
                        initializationCallback.onInitializationComplete(false);
                    }
                });
            }
        });
    }

    private void initializeEagleProfiler() {
        try {
            Log.d(TAG, "Initializing EagleProfiler on background thread...");

            if (PICOVOICE_ACCESS_KEY == null || "YOUR_PICOVOICE_ACCESS_KEY_HERE".equals(PICOVOICE_ACCESS_KEY)) {
                Log.e(TAG, "PicoVoice access key not configured!");
                isInitialized = false;
                return;
            }

            eagleProfiler = new EagleProfiler.Builder()
                    .setAccessKey(PICOVOICE_ACCESS_KEY)
                    .build(context);

            minEnrollSamples = eagleProfiler.getMinEnrollSamples();
            isInitialized = true;
            Log.d(TAG, "✅ EagleProfiler initialized successfully. MinEnrollSamples: " + minEnrollSamples);

        } catch (EagleException e) {
            Log.e(TAG, "❌ Failed to initialize EagleProfiler: " + e.getMessage(), e);
            eagleProfiler = null;
            isInitialized = false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected error initializing EagleProfiler: " + e.getMessage(), e);
            eagleProfiler = null;
            isInitialized = false;
        }
    }

    // Callback interfaces
    public interface InitializationCallback {
        void onInitializationComplete(boolean success);
    }

    public interface CreateProfileCallback {
        void onProfileCreated(String profileId, String userName);
        void onError(String error);
    }

    public interface EnrollmentCallback {
        void onRecordingStarted();
        void onEnrollmentComplete(String profileId, String userName);
        void onEnrollmentProgress(int remainingSeconds);
        void onError(String error);
    }

    public interface SpeakerIdentificationCallback {
        void onSpeakerIdentified(SpeakerProfile profile);
        void onIdentificationFailed(String error);
        void onError(String error);
    }

    public interface DeleteProfileCallback {
        void onProfileDeleted(String profileId);
        void onError(String error);
    }

    /**
     * Set initialization callback for UI updates
     */
    public void setInitializationCallback(InitializationCallback callback) {
        this.initializationCallback = callback;

        // If already initialized, call immediately
        if (!isInitializing) {
            mainHandler.post(() -> callback.onInitializationComplete(isInitialized));
        }
    }

    /**
     * Speaker Profile class (unchanged from original)
     */
    public static class SpeakerProfile {
        private String profileId;
        private String userName;
        private transient EagleProfile eagleProfile;
        private byte[] eagleProfileData;
        private long lastIdentified;
        private String enrollmentStatus;
        private int remainingEnrollmentsSpeechLength;

        // Lighting preferences
        private int preferredBrightness = 80;
        private String preferredColor = "#FFDD99";
        private String preferredSunsetTime = "18:30";
        private String preferredBedtime = "22:00";
        private boolean autoScheduleEnabled = true;

        public SpeakerProfile(String profileId, String userName) {
            this.profileId = profileId;
            this.userName = userName;
            this.lastIdentified = 0;
            this.enrollmentStatus = "Enrolling";
            this.remainingEnrollmentsSpeechLength = 20;
        }

        // All getters and setters remain the same as original...
        public String getProfileId() { return profileId; }
        public String getUserName() { return userName; }
        public EagleProfile getEagleProfile() { return eagleProfile; }

        public void setEagleProfile(EagleProfile profile) {
            this.eagleProfile = profile;
            if (profile != null) {
                try {
                    this.eagleProfileData = profile.getBytes();
                } catch (Exception e) {
                    Log.e("SpeakerProfile", "Failed to serialize Eagle profile", e);
                }
            }
        }

        public byte[] getEagleProfileData() { return eagleProfileData; }
        public void setEagleProfileData(byte[] data) {
            this.eagleProfileData = data;
            if (data != null) {
                try {
                    this.eagleProfile = new EagleProfile(data);
                } catch (Exception e) {
                    Log.e("SpeakerProfile", "Failed to deserialize Eagle profile", e);
                }
            }
        }

        public long getLastIdentified() { return lastIdentified; }
        public void setLastIdentified(long lastIdentified) { this.lastIdentified = lastIdentified; }
        public String getEnrollmentStatus() { return enrollmentStatus; }
        public void setEnrollmentStatus(String status) { this.enrollmentStatus = status; }
        public int getRemainingEnrollmentsSpeechLength() { return remainingEnrollmentsSpeechLength; }
        public void setRemainingEnrollmentsSpeechLength(int remaining) { this.remainingEnrollmentsSpeechLength = remaining; }

        public int getPreferredBrightness() { return preferredBrightness; }
        public void setPreferredBrightness(int brightness) { this.preferredBrightness = brightness; }
        public String getPreferredColor() { return preferredColor; }
        public void setPreferredColor(String color) { this.preferredColor = color; }
        public String getPreferredSunsetTime() { return preferredSunsetTime; }
        public void setPreferredSunsetTime(String time) { this.preferredSunsetTime = time; }
        public String getPreferredBedtime() { return preferredBedtime; }
        public void setPreferredBedtime(String time) { this.preferredBedtime = time; }
        public boolean isAutoScheduleEnabled() { return autoScheduleEnabled; }
        public void setAutoScheduleEnabled(boolean enabled) { this.autoScheduleEnabled = enabled; }
    }

    /**
     * Load stored speaker profiles from SharedPreferences - runs on background thread
     */
    private void loadStoredProfiles() {
        try {
            Log.d(TAG, "Loading profiles from storage on background thread...");
            String profilesJson = preferences.getString(PREFS_KEY_PROFILES, "[]");
            Type listType = new TypeToken<List<SpeakerProfile>>(){}.getType();
            List<SpeakerProfile> storedProfiles = gson.fromJson(profilesJson, listType);

            if (storedProfiles != null) {
                synchronized (enrolledSpeakers) {
                    enrolledSpeakers.clear();
                    for (SpeakerProfile profile : storedProfiles) {
                        if (profile.getEagleProfileData() != null) {
                            try {
                                EagleProfile eagleProfile = new EagleProfile(profile.getEagleProfileData());
                                profile.setEagleProfile(eagleProfile);
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to reconstruct Eagle profile for " + profile.getUserName(), e);
                                profile.setEnrollmentStatus("Corrupted - Re-enroll needed");
                            }
                        }
                        enrolledSpeakers.add(profile);
                    }
                }
                Log.d(TAG, "✅ Loaded " + storedProfiles.size() + " stored speaker profiles");

                // Notify UI about loaded profiles
                mainHandler.post(() -> {
                    if (initializationCallback != null) {
                        initializationCallback.onInitializationComplete(isInitialized);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to load stored profiles", e);
        }
    }

    /**
     * Save speaker profiles to SharedPreferences - runs on background thread
     */
    private void saveProfilesToStorage() {
        backgroundExecutor.execute(() -> {
            try {
                synchronized (enrolledSpeakers) {
                    // Ensure all profiles have their Eagle data serialized
                    for (SpeakerProfile profile : enrolledSpeakers) {
                        if (profile.getEagleProfile() != null && profile.getEagleProfileData() == null) {
                            try {
                                profile.setEagleProfileData(profile.getEagleProfile().getBytes());
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to serialize profile for " + profile.getUserName(), e);
                            }
                        }
                    }

                    String profilesJson = gson.toJson(enrolledSpeakers);
                    preferences.edit()
                            .putString(PREFS_KEY_PROFILES, profilesJson)
                            .apply();

                    Log.d(TAG, "✅ Saved " + enrolledSpeakers.size() + " speaker profiles to storage");
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to save profiles to storage", e);
            }
        });
    }

    /**
     * Create a new speaker profile for enrollment - thread-safe
     */
    public void createSpeakerProfile(String userName, CreateProfileCallback callback) {
        // Don't wait for Eagle initialization for profile creation
        backgroundExecutor.execute(() -> {
            try {
                String profileId = "profile_" + System.currentTimeMillis();
                SpeakerProfile profile = new SpeakerProfile(profileId, userName);

                synchronized (enrolledSpeakers) {
                    enrolledSpeakers.add(profile);
                }

                saveProfilesToStorage();

                mainHandler.post(() -> callback.onProfileCreated(profileId, userName));

            } catch (Exception e) {
                Log.e(TAG, "Error creating speaker profile", e);
                mainHandler.post(() -> callback.onError("Failed to create profile: " + e.getMessage()));
            }
        });
    }

    /**
     * Start enrollment process for a speaker - thread-safe
     */
    public void enrollSpeaker(String profileId, EnrollmentCallback callback) {
        // Check Eagle initialization before enrollment
        if (!isInitialized || eagleProfiler == null) {
            callback.onError("Eagle not properly initialized. Please check your PicoVoice access key and restart the app.");
            return;
        }

        backgroundExecutor.execute(() -> {
            SpeakerProfile profile = findProfileById(profileId);
            if (profile == null) {
                mainHandler.post(() -> callback.onError("Profile not found"));
                return;
            }

            if (isRecording) {
                mainHandler.post(() -> callback.onError("Already recording"));
                return;
            }

            currentEnrollmentProfile = profile;
            currentEnrollmentCallback = callback;
            isEnrolling = true;
            isRecording = true;
            enrollmentBuffer.clear();
            enrollmentStartTime = System.currentTimeMillis();
            totalEnrollmentFrames = 0;

            try {
                // Reset profiler for this enrollment session
                eagleProfiler.reset();

                mainHandler.post(() -> callback.onRecordingStarted());

                // Start voice processor on main thread
                mainHandler.post(() -> {
                    try {
                        voiceProcessor.addFrameListener(this::processEnrollmentFrame);
                        voiceProcessor.start(512, 16000);
                        Log.d(TAG, "Started enrollment for " + profile.getUserName());
                    } catch (VoiceProcessorException e) {
                        Log.e(TAG, "Failed to start enrollment recording", e);
                        stopRecording();
                        callback.onError("Failed to start recording: " + e.getMessage());
                    }
                });

            } catch (EagleException e) {
                Log.e(TAG, "Eagle error during enrollment start", e);
                isEnrolling = false;
                isRecording = false;
                currentEnrollmentCallback = null;
                mainHandler.post(() -> callback.onError("Eagle error: " + e.getMessage()));
            }
        });
    }

    // Rest of the methods remain the same but with proper thread safety...

    private void processEnrollmentFrame(short[] pcm) {
        if (!isEnrolling || currentEnrollmentProfile == null) {
            return;
        }

        // Process audio on background thread to avoid blocking UI
        backgroundExecutor.execute(() -> {
            if (!isEnrolling || currentEnrollmentProfile == null || eagleProfiler == null) {
                return;
            }

            try {
                totalEnrollmentFrames++;

                // Add PCM data to enrollment buffer
                synchronized (enrollmentBuffer) {
                    for (short sample : pcm) {
                        enrollmentBuffer.add(sample);
                    }

                    // Process when we have enough samples
                    if (enrollmentBuffer.size() >= minEnrollSamples) {
                        short[] pcmArray = new short[enrollmentBuffer.size()];
                        for (int i = 0; i < enrollmentBuffer.size(); i++) {
                            pcmArray[i] = enrollmentBuffer.get(i);
                        }
                        enrollmentBuffer.clear();

                        EagleProfilerEnrollResult result = eagleProfiler.enroll(pcmArray);
                        EagleProfilerEnrollFeedback feedback = result.getFeedback();
                        float percentage = result.getPercentage();
                        long currentTime = System.currentTimeMillis();
                        long enrollmentDuration = currentTime - enrollmentStartTime;
                        int remainingSeconds = Math.max(0, (int) ((MIN_ENROLLMENT_DURATION_MS - enrollmentDuration) / 1000));

                        currentEnrollmentProfile.setRemainingEnrollmentsSpeechLength(remainingSeconds);

                        Log.d(TAG, "Enrollment progress: " + (percentage * 100) + "%, feedback: " + feedback +
                                ", duration: " + (enrollmentDuration / 1000) + "s, frames: " + totalEnrollmentFrames);

                        // Check completion criteria
                        boolean hasMinimumDuration = enrollmentDuration >= MIN_ENROLLMENT_DURATION_MS;
                        boolean hasGoodPercentage = percentage >= 0.95f;
                        boolean hasEnoughFrames = totalEnrollmentFrames >= 200;

                        if (hasMinimumDuration && hasGoodPercentage && hasEnoughFrames &&
                                feedback == EagleProfilerEnrollFeedback.AUDIO_OK) {
                            Log.d(TAG, "Enrollment criteria met - finishing enrollment");
                            finishEnrollment();
                        } else if (enrollmentDuration > MAX_ENROLLMENT_DURATION_MS) {
                            Log.w(TAG, "Enrollment timeout - forcing completion");
                            finishEnrollment();
                        } else {
                            // Continue enrollment - report progress on main thread
                            if (currentEnrollmentCallback != null) {
                                mainHandler.post(() -> {
                                    if (currentEnrollmentCallback != null) {
                                        currentEnrollmentCallback.onEnrollmentProgress(remainingSeconds);
                                    }
                                });
                            }
                        }
                    }
                }

            } catch (EagleException e) {
                Log.e(TAG, "Enrollment processing error", e);
                mainHandler.post(() -> {
                    stopRecording();
                    if (currentEnrollmentCallback != null) {
                        currentEnrollmentCallback.onError("Enrollment error: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during enrollment processing", e);
                mainHandler.post(() -> {
                    stopRecording();
                    if (currentEnrollmentCallback != null) {
                        currentEnrollmentCallback.onError("Unexpected error: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void finishEnrollment() {
        backgroundExecutor.execute(() -> {
            try {
                mainHandler.post(this::stopRecording);

                if (currentEnrollmentProfile != null && eagleProfiler != null) {
                    Log.d(TAG, "Attempting to export Eagle profile for " + currentEnrollmentProfile.getUserName());

                    EagleProfile eagleProfile = eagleProfiler.export();
                    currentEnrollmentProfile.setEagleProfile(eagleProfile);
                    currentEnrollmentProfile.setEnrollmentStatus("Enrolled");
                    currentEnrollmentProfile.setRemainingEnrollmentsSpeechLength(0);

                    saveProfilesToStorage();

                    if (currentEnrollmentCallback != null) {
                        mainHandler.post(() -> {
                            if (currentEnrollmentCallback != null) {
                                currentEnrollmentCallback.onEnrollmentComplete(
                                        currentEnrollmentProfile.getProfileId(),
                                        currentEnrollmentProfile.getUserName()
                                );
                            }
                        });
                    }

                    Log.d(TAG, "✅ Enrollment completed successfully for " + currentEnrollmentProfile.getUserName());

                } else {
                    mainHandler.post(() -> {
                        if (currentEnrollmentCallback != null) {
                            if (eagleProfiler == null) {
                                currentEnrollmentCallback.onError("Eagle service not available");
                            } else {
                                currentEnrollmentCallback.onError("Profile data lost");
                            }
                        }
                    });
                }

            } catch (EagleException e) {
                Log.e(TAG, "❌ Failed to export profile: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    if (currentEnrollmentCallback != null) {
                        if (e.getMessage() != null && e.getMessage().contains("before enrollment is complete")) {
                            currentEnrollmentCallback.onEnrollmentProgress(10);
                            currentEnrollmentCallback.onError("Please speak for longer. Need more clear speech for enrollment.");
                        } else {
                            currentEnrollmentCallback.onError("Failed to complete enrollment: " + e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error finishing enrollment", e);
                mainHandler.post(() -> {
                    if (currentEnrollmentCallback != null) {
                        currentEnrollmentCallback.onError("Unexpected error: " + e.getMessage());
                    }
                });
            } finally {
                currentEnrollmentProfile = null;
                currentEnrollmentCallback = null;
                isEnrolling = false;
                enrollmentStartTime = 0;
                totalEnrollmentFrames = 0;
            }
        });
    }

    public void identifySpeaker(SpeakerIdentificationCallback callback) {
        if (isRecording) {
            callback.onError("Already recording");
            return;
        }

        if (!isInitialized || eagleProfiler == null) {
            callback.onError("Eagle not properly initialized. Please check your PicoVoice access key and restart the app.");
            return;
        }

        backgroundExecutor.execute(() -> {
            List<EagleProfile> enrolledProfiles = new ArrayList<>();
            List<SpeakerProfile> enrolledSpeakerProfiles = new ArrayList<>();

            synchronized (enrolledSpeakers) {
                for (SpeakerProfile profile : enrolledSpeakers) {
                    if ("Enrolled".equals(profile.getEnrollmentStatus()) && profile.getEagleProfile() != null) {
                        enrolledProfiles.add(profile.getEagleProfile());
                        enrolledSpeakerProfiles.add(profile);
                    }
                }
            }

            if (enrolledProfiles.isEmpty()) {
                mainHandler.post(() -> callback.onIdentificationFailed("No enrolled speakers available"));
                return;
            }

            try {
                EagleProfile[] profileArray = enrolledProfiles.toArray(new EagleProfile[0]);
                eagle = new Eagle.Builder()
                        .setSpeakerProfiles(profileArray)
                        .setAccessKey(PICOVOICE_ACCESS_KEY)
                        .build(context);

                smoothScores = new float[enrolledProfiles.size()];
                identificationFrameCount = 0;
                isIdentifying = true;
                isRecording = true;

                mainHandler.post(() -> {
                    try {
                        voiceProcessor.addFrameListener(pcm -> processIdentificationFrame(pcm, callback, enrolledSpeakerProfiles));
                        voiceProcessor.start(eagle.getFrameLength(), eagle.getSampleRate());
                        Log.d(TAG, "Started speaker identification");
                    } catch (VoiceProcessorException e) {
                        Log.e(TAG, "Failed to start identification recording", e);
                        callback.onError("Failed to start recording: " + e.getMessage());
                    }
                });

            } catch (EagleException e) {
                Log.e(TAG, "Failed to initialize Eagle for identification", e);
                mainHandler.post(() -> callback.onError("Failed to start identification: " + e.getMessage()));
            }
        });
    }

    private void processIdentificationFrame(short[] pcm, SpeakerIdentificationCallback callback,
                                            List<SpeakerProfile> enrolledSpeakerProfiles) {
        if (!isIdentifying || eagle == null) {
            return;
        }

        backgroundExecutor.execute(() -> {
            if (!isIdentifying || eagle == null) {
                return;
            }

            try {
                float[] scores = eagle.process(pcm);
                identificationFrameCount++;

                // Apply smoothing
                if (smoothScores == null) {
                    smoothScores = new float[scores.length];
                    System.arraycopy(scores, 0, smoothScores, 0, scores.length);
                } else {
                    for (int i = 0; i < scores.length; i++) {
                        smoothScores[i] = 0.8f * smoothScores[i] + 0.2f * scores[i];
                    }
                }

                // Check for identification after minimum frames
                if (identificationFrameCount >= MIN_IDENTIFICATION_FRAMES) {
                    int maxIndex = -1;
                    float maxScore = 0.0f;
                    for (int i = 0; i < smoothScores.length; i++) {
                        if (smoothScores[i] > maxScore) {
                            maxScore = smoothScores[i];
                            maxIndex = i;
                        }
                    }

                    if (maxScore > 0.7f && maxIndex >= 0 && maxIndex < enrolledSpeakerProfiles.size()) {
                        SpeakerProfile identifiedSpeaker = enrolledSpeakerProfiles.get(maxIndex);
                        identifiedSpeaker.setLastIdentified(System.currentTimeMillis());

                        saveProfilesToStorage();

                        mainHandler.post(() -> {
                            stopRecording();
                            isIdentifying = false;
                            callback.onSpeakerIdentified(identifiedSpeaker);
                        });

                        Log.d(TAG, "Speaker identified: " + identifiedSpeaker.getUserName() +
                                " (confidence: " + (maxScore * 100) + "%)");
                        return;
                    }
                }

                // Continue identification for a maximum time
                if (identificationFrameCount > 160) {
                    mainHandler.post(() -> {
                        stopRecording();
                        isIdentifying = false;
                        callback.onIdentificationFailed("Could not identify speaker with sufficient confidence");
                    });
                }

            } catch (EagleException e) {
                Log.e(TAG, "Identification processing error", e);
                mainHandler.post(() -> {
                    stopRecording();
                    isIdentifying = false;
                    callback.onError("Identification error: " + e.getMessage());
                });
            }
        });
    }

    // Thread-safe utility methods
    public void updateSpeakerPreferences(String profileId, int brightness, String color,
                                         String sunsetTime, String bedtime, boolean autoSchedule) {
        backgroundExecutor.execute(() -> {
            SpeakerProfile profile = findProfileById(profileId);
            if (profile != null) {
                profile.setPreferredBrightness(brightness);
                profile.setPreferredColor(color);
                profile.setPreferredSunsetTime(sunsetTime);
                profile.setPreferredBedtime(bedtime);
                profile.setAutoScheduleEnabled(autoSchedule);
                saveProfilesToStorage();
                Log.d(TAG, "Updated preferences for " + profile.getUserName());
            }
        });
    }

    public void deleteSpeakerProfile(String profileId, DeleteProfileCallback callback) {
        backgroundExecutor.execute(() -> {
            SpeakerProfile profile = findProfileById(profileId);
            if (profile != null) {
                synchronized (enrolledSpeakers) {
                    enrolledSpeakers.remove(profile);
                }
                saveProfilesToStorage();
                mainHandler.post(() -> callback.onProfileDeleted(profileId));
                Log.d(TAG, "Deleted profile: " + profileId);
            } else {
                mainHandler.post(() -> callback.onError("Profile not found"));
            }
        });
    }

    private SpeakerProfile findProfileById(String profileId) {
        synchronized (enrolledSpeakers) {
            for (SpeakerProfile profile : enrolledSpeakers) {
                if (profile.getProfileId().equals(profileId)) {
                    return profile;
                }
            }
        }
        return null;
    }

    public void stopRecording() {
        if (isRecording) {
            try {
                voiceProcessor.stop();
                voiceProcessor.clearFrameListeners();
                isRecording = false;
                isEnrolling = false;
                isIdentifying = false;
                Log.d(TAG, "Recording stopped");
            } catch (VoiceProcessorException e) {
                Log.e(TAG, "Error stopping voice processor", e);
            }
        }
    }

    public void clearStoredProfiles() {
        backgroundExecutor.execute(() -> {
            preferences.edit().remove(PREFS_KEY_PROFILES).apply();
            synchronized (enrolledSpeakers) {
                enrolledSpeakers.clear();
            }
            Log.d(TAG, "Cleared all stored speaker profiles");
        });
    }

    public List<SpeakerProfile> getEnrolledSpeakers() {
        synchronized (enrolledSpeakers) {
            return new ArrayList<>(enrolledSpeakers);
        }
    }

    public String getStorageInfo() {
        synchronized (enrolledSpeakers) {
            int storedCount = enrolledSpeakers.size();
            long enrolledCount = enrolledSpeakers.stream()
                    .filter(p -> "Enrolled".equals(p.getEnrollmentStatus()))
                    .count();
            return String.format(java.util.Locale.US, "Storage: %d profiles (%d enrolled, %d pending)",
                    storedCount, enrolledCount, storedCount - enrolledCount);
        }
    }

    public boolean isInitialized() {
        return isInitialized && !isInitializing;
    }

    public boolean isInitializing() {
        return isInitializing;
    }

    public String getInitializationStatus() {
        if (isInitializing) {
            return "Initializing Eagle services...";
        }

        if (!isInitialized) {
            if (PICOVOICE_ACCESS_KEY == null || "YOUR_PICOVOICE_ACCESS_KEY_HERE".equals(PICOVOICE_ACCESS_KEY)) {
                return "PicoVoice access key not configured";
            } else {
                return "EagleProfiler initialization failed - check access key validity";
            }
        }
        return "Eagle services initialized successfully";
    }

    /**
     * Clean up resources - thread-safe
     */
    public void cleanup() {
        stopRecording();

        backgroundExecutor.execute(() -> {
            if (eagle != null) {
                eagle.delete();
                eagle = null;
            }

            if (eagleProfiler != null) {
                eagleProfiler.delete();
                eagleProfiler = null;
            }

            isInitialized = false;
            isInitializing = false;
        });

        // Shutdown background thread and executor
        backgroundExecutor.shutdown();
        backgroundThread.quitSafely();

        synchronized (enrolledSpeakers) {
            enrolledSpeakers.clear();
        }

        Log.d(TAG, "EagleSpeakerIdentificationService cleaned up");
    }
}