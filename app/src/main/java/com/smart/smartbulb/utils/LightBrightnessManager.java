package com.smart.smartbulb.utils;



import com.smart.smartbulb.api.AmbientLightApiService;
import com.smart.smartbulb.models.LightSettings;

/**
 * Manages the relationship between ambient light and bulb brightness
 * Ensures that ambient + bulb brightness = 100%
 */
public class LightBrightnessManager {

    public interface BrightnessUpdateListener {
        void onBrightnessCalculated(int newBulbBrightness, int ambientPercentage, String calculationReason);
        void onError(String error);
    }

    private static final String TAG = "LightBrightnessManager";
    private static final int MIN_BULB_BRIGHTNESS = 5;  // Minimum bulb brightness
    private static final int MAX_BULB_BRIGHTNESS = 95; // Maximum bulb brightness

    /**
     * Calculate optimal bulb brightness based on ambient light
     * Formula: Bulb Brightness = 100 - Ambient Light Percentage
     */
    public static int calculateOptimalBulbBrightness(int ambientLightPercentage) {
        // Ensure ambient light percentage is within valid range
        ambientLightPercentage = Math.max(0, Math.min(100, ambientLightPercentage));

        // Calculate complementary brightness
        int calculatedBrightness = 100 - ambientLightPercentage;

        // Apply constraints
        return Math.max(MIN_BULB_BRIGHTNESS, Math.min(MAX_BULB_BRIGHTNESS, calculatedBrightness));
    }

    /**
     * Update bulb brightness based on current ambient light data
     */
    public static void updateBulbBrightnessFromAmbient(
            AmbientLightApiService.AmbientLightData ambientData,
            LightSettings lightSettings,
            BrightnessUpdateListener listener) {

        if (ambientData == null) {
            if (listener != null) {
                listener.onError("No ambient light data available");
            }
            return;
        }

        // Get ambient light percentage
        int ambientPercentage = ambientData.getPercentageAsInt();

        // Calculate optimal bulb brightness
        int optimalBrightness = calculateOptimalBulbBrightness(ambientPercentage);

        // Determine reason for calculation
        String reason = getCalculationReason(ambientPercentage, optimalBrightness);

        // Update light settings
        lightSettings.setBrightness(optimalBrightness);

        // Notify listener
        if (listener != null) {
            listener.onBrightnessCalculated(optimalBrightness, ambientPercentage, reason);
        }
    }

    /**
     * Get current total brightness (ambient + bulb)
     */
    public static int getTotalBrightness(int ambientPercentage, int bulbBrightness) {
        return ambientPercentage + bulbBrightness;
    }

    /**
     * Check if current brightness combination is optimal
     */
    public static boolean isOptimalBrightness(int ambientPercentage, int bulbBrightness) {
        int total = getTotalBrightness(ambientPercentage, bulbBrightness);
        return total >= 95 && total <= 105; // Allow 5% tolerance
    }

    /**
     * Get ambient light percentage needed for a specific bulb brightness
     */
    public static int getRequiredAmbientForBulbBrightness(int targetBulbBrightness) {
        return Math.max(0, Math.min(100, 100 - targetBulbBrightness));
    }

    /**
     * Get brightness adjustment recommendation
     */
    public static BrightnessRecommendation getBrightnessRecommendation(
            int currentAmbient, int currentBulb) {

        int total = getTotalBrightness(currentAmbient, currentBulb);
        int optimal = calculateOptimalBulbBrightness(currentAmbient);

        if (isOptimalBrightness(currentAmbient, currentBulb)) {
            return new BrightnessRecommendation(
                    BrightnessRecommendation.Type.OPTIMAL,
                    "Brightness levels are optimal",
                    currentBulb
            );
        } else if (currentBulb < optimal) {
            return new BrightnessRecommendation(
                    BrightnessRecommendation.Type.INCREASE,
                    "Consider increasing bulb brightness to " + optimal + "%",
                    optimal
            );
        } else {
            return new BrightnessRecommendation(
                    BrightnessRecommendation.Type.DECREASE,
                    "Consider decreasing bulb brightness to " + optimal + "%",
                    optimal
            );
        }
    }

    private static String getCalculationReason(int ambientPercentage, int calculatedBrightness) {
        if (ambientPercentage < 10) {
            return "Very low ambient light detected - high bulb brightness needed";
        } else if (ambientPercentage < 30) {
            return "Low ambient light - moderate bulb brightness";
        } else if (ambientPercentage < 70) {
            return "Moderate ambient light - balanced brightness";
        } else if (ambientPercentage < 90) {
            return "High ambient light - low bulb brightness needed";
        } else {
            return "Very bright ambient light - minimal bulb brightness";
        }
    }

    /**
     * Brightness recommendation data class
     */
    public static class BrightnessRecommendation {
        public enum Type {
            OPTIMAL, INCREASE, DECREASE
        }

        private final Type type;
        private final String message;
        private final int recommendedBrightness;

        public BrightnessRecommendation(Type type, String message, int recommendedBrightness) {
            this.type = type;
            this.message = message;
            this.recommendedBrightness = recommendedBrightness;
        }

        public Type getType() { return type; }
        public String getMessage() { return message; }
        public int getRecommendedBrightness() { return recommendedBrightness; }
    }
}