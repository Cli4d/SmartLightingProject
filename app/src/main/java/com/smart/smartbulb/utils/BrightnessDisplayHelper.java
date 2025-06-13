package com.smart.smartbulb.utils;

// BrightnessDisplayHelper.java

import android.content.Context;
import android.graphics.Color;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.smart.smartbulb.R;
import com.smart.smartbulb.api.AmbientLightApiService;

import java.util.Locale;

/**
 * Helper class for displaying brightness information in the UI
 * Handles the visual representation of ambient + bulb brightness = 100%
 */
public class BrightnessDisplayHelper {

    /**
     * Update ambient light display with complementary brightness info
     */
    public static void updateAmbientLightDisplay(
            Context context,
            TextView textView,
            ProgressBar progressBar,
            AmbientLightApiService.AmbientLightData ambientData,
            int bulbBrightness) {

        if (ambientData == null) {
            textView.setText("Ambient Light: No data");
            progressBar.setProgress(0);
            return;
        }

        int ambientPercentage = ambientData.getPercentageAsInt();
        int totalBrightness = LightBrightnessManager.getTotalBrightness(ambientPercentage, bulbBrightness);

        // Create detailed display text
        String displayText = String.format(Locale.getDefault(),
                "%s (%d%%) • %.1f lux\nBulb: %d%% • Total: %d%%",
                ambientData.getLuxDescription(),
                ambientPercentage,
                ambientData.getLux(),
                bulbBrightness,
                totalBrightness
        );

        textView.setText(displayText);
        progressBar.setProgress(ambientPercentage);

        // Color code the progress bar based on optimization
        updateProgressBarColor(progressBar, ambientPercentage, bulbBrightness);
    }

    /**
     * Update bulb brightness display with complementary info
     */
    public static void updateBulbBrightnessDisplay(
            Context context,
            TextView textView,
            int bulbBrightness,
            int ambientPercentage) {

        int totalBrightness = LightBrightnessManager.getTotalBrightness(ambientPercentage, bulbBrightness);
        boolean isOptimal = LightBrightnessManager.isOptimalBrightness(ambientPercentage, bulbBrightness);

        String displayText = String.format(Locale.getDefault(),
                "Bulb: %d%% (Ambient: %d%%, Total: %d%%)",
                bulbBrightness,
                ambientPercentage,
                totalBrightness
        );

        if (!isOptimal) {
            displayText += " ⚠️";
        }

        textView.setText(displayText);
    }

    /**
     * Create a brightness summary card text
     */
    public static String createBrightnessSummary(
            AmbientLightApiService.AmbientLightData ambientData,
            int bulbBrightness) {

        if (ambientData == null) {
            return "Brightness Summary: No ambient data available";
        }

        int ambientPercentage = ambientData.getPercentageAsInt();
        int totalBrightness = LightBrightnessManager.getTotalBrightness(ambientPercentage, bulbBrightness);
        boolean isOptimal = LightBrightnessManager.isOptimalBrightness(ambientPercentage, bulbBrightness);

        StringBuilder summary = new StringBuilder();
        summary.append("💡 Brightness Balance\n");
        summary.append(String.format(Locale.getDefault(),
                "Ambient: %d%% + Bulb: %d%% = %d%%\n",
                ambientPercentage, bulbBrightness, totalBrightness));

        if (isOptimal) {
            summary.append("✅ Optimal balance achieved");
        } else {
            LightBrightnessManager.BrightnessRecommendation rec =
                    LightBrightnessManager.getBrightnessRecommendation(ambientPercentage, bulbBrightness);
            summary.append("💡 ").append(rec.getMessage());
        }

        return summary.toString();
    }

    /**
     * Get brightness status icon
     */
    public static String getBrightnessStatusIcon(int ambientPercentage, int bulbBrightness) {
        boolean isOptimal = LightBrightnessManager.isOptimalBrightness(ambientPercentage, bulbBrightness);

        if (isOptimal) {
            return "✅";
        } else {
            int total = LightBrightnessManager.getTotalBrightness(ambientPercentage, bulbBrightness);
            if (total < 95) {
                return "📈"; // Needs more brightness
            } else {
                return "📉"; // Too much brightness
            }
        }
    }

    /**
     * Update progress bar color based on optimization status
     */
    private static void updateProgressBarColor(ProgressBar progressBar, int ambientPercentage, int bulbBrightness) {
        boolean isOptimal = LightBrightnessManager.isOptimalBrightness(ambientPercentage, bulbBrightness);

        if (isOptimal) {
            // Green for optimal
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
        } else {
            // Orange for suboptimal
            progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800")));
        }
    }

    /**
     * Format brightness percentage with status
     */
    public static String formatBrightnessWithStatus(int percentage, boolean isOptimal) {
        String status = isOptimal ? " ✅" : " ⚠️";
        return String.format(Locale.getDefault(), "%d%%%s", percentage, status);
    }

    /**
     * Get brightness level description
     */
    public static String getBrightnessLevelDescription(int percentage) {
        if (percentage < 10) return "Very Low";
        else if (percentage < 30) return "Low";
        else if (percentage < 50) return "Medium-Low";
        else if (percentage < 70) return "Medium";
        else if (percentage < 85) return "High";
        else return "Very High";
    }
}