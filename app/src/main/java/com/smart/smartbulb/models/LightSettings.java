package com.smart.smartbulb.models;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class LightSettings implements Serializable {
    private static final long serialVersionUID = 1L;

    // Light state properties
    private boolean isOn = true;
    private boolean bulbOn = true; // Separate bulb state tracking
    private int brightness = 80;
    private String color = "#FFDD99"; // Warm white default
    private boolean dimmed = false; // Track if currently dimmed

    // Schedule properties
    private String bedtime = "22:00";
    private String sunsetTime = "18:30";
    private boolean autoScheduleEnabled = true;
    private boolean autoModeEnabled = false; // Ambient light auto mode

    // User properties
    private String username = "User";

    // Ambient light level (0-100%)
    private int ambientLightLevel = 50;

    // State change tracking
    private boolean stateChanged = false;
    private int previousBrightness = 80;

    // Brightness constants
    public static final int SUNSET_BRIGHTNESS = 80;
    public static final int BEDTIME_BRIGHTNESS = 20;
    public static final int MAX_BRIGHTNESS = 100;
    public static final int MIN_BRIGHTNESS = 1;

    // Default colors
    public static final String WARM_WHITE = "#FFBB66";
    public static final String SOFT_YELLOW = "#FFDD99";
    public static final String PURE_WHITE = "#FFFFFF";
    public static final String COOL_WHITE = "#BBEEFF";
    public static final String SOFT_BLUE = "#99CCFF";
    public static final String LAVENDER = "#CC99FF";
    public static final String SOFT_RED = "#FFAAAA";
    public static final String SOFT_GREEN = "#AAFFAA";

    // Constructors
    public LightSettings() {
        // Default constructor with sensible defaults
    }

    public LightSettings(boolean isOn, int brightness, String color) {
        this.isOn = isOn;
        this.bulbOn = isOn;
        setBrightness(brightness); // Use setter for validation
        this.color = color != null ? color : SOFT_YELLOW;
    }

    public LightSettings(boolean isOn, int brightness, String color, String bedtime,
                         String sunsetTime, boolean autoScheduleEnabled, String username) {
        this(isOn, brightness, color);
        this.bedtime = bedtime != null ? bedtime : "22:00";
        this.sunsetTime = sunsetTime != null ? sunsetTime : "18:30";
        this.autoScheduleEnabled = autoScheduleEnabled;
        this.username = username != null ? username : "User";
    }

    // Light state getters and setters
    public boolean isOn() {
        return isOn;
    }

    public void setOn(boolean on) {
        isOn = on;
        bulbOn = on;
    }

    public boolean isBulbOn() {
        return bulbOn;
    }

    public void setBulbOn(boolean bulbOn) {
        this.bulbOn = bulbOn;
        this.isOn = bulbOn;
    }

    public int getBrightness() {
        return brightness;
    }

    public void setBrightness(int brightness) {
        this.previousBrightness = this.brightness; // Store previous value
        this.brightness = Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, brightness));
        this.stateChanged = true; // Mark that state has changed
    }

    public String getColor() {
        return color != null ? color : SOFT_YELLOW;
    }

    public void setColor(String color) {
        this.color = color != null ? color : SOFT_YELLOW;
    }

    // Dimming state
    public boolean isDimmed() {
        return dimmed;
    }

    public void setDimmed(boolean dimmed) {
        this.dimmed = dimmed;
    }

    // Ambient light level
    public int getAmbientLightLevel() {
        return ambientLightLevel;
    }

    public void setAmbientLightLevel(int ambientLightLevel) {
        this.ambientLightLevel = Math.max(0, Math.min(100, ambientLightLevel));
    }

    // Schedule getters and setters
    public String getBedtime() {
        return bedtime != null ? bedtime : "22:00";
    }

    public void setBedtime(String bedtime) {
        this.bedtime = bedtime;
    }

    public String getSunsetTime() {
        return sunsetTime != null ? sunsetTime : "18:30";
    }

    public void setSunsetTime(String sunsetTime) {
        this.sunsetTime = sunsetTime;
    }

    public boolean isAutoScheduleEnabled() {
        return autoScheduleEnabled;
    }

    public void setAutoScheduleEnabled(boolean autoScheduleEnabled) {
        this.autoScheduleEnabled = autoScheduleEnabled;
    }

    public boolean isAutoModeEnabled() {
        return autoModeEnabled;
    }

    public void setAutoModeEnabled(boolean autoModeEnabled) {
        this.autoModeEnabled = autoModeEnabled;
    }

    // User properties
    public String getUsername() {
        return username != null ? username : "User";
    }

    public void setUsername(String username) {
        this.username = username;
    }

    // Brightness preset methods
    public void setSunsetBrightness() {
        setBrightness(SUNSET_BRIGHTNESS);
    }

    public void setBedtimeBrightness() {
        setBrightness(BEDTIME_BRIGHTNESS);
    }

    public void setMaxBrightness() {
        setBrightness(MAX_BRIGHTNESS);
    }

    public void dimTo(int targetBrightness) {
        setBrightness(targetBrightness);
        setDimmed(targetBrightness < 50); // Consider dimmed if below 50%
    }

    // State change tracking methods
    public boolean hasStateChanged() {
        return stateChanged;
    }

    public void setStateChanged(boolean stateChanged) {
        this.stateChanged = stateChanged;
    }

    public int getPreviousBrightness() {
        return previousBrightness;
    }

    public void setPreviousBrightness(int previousBrightness) {
        this.previousBrightness = previousBrightness;
    }

    // Methods that were missing and causing errors
    public void cancelDimming() {
        setDimmed(false);
        setBrightness(80); // Return to normal brightness
    }

    // Time checking methods
    public boolean isSunsetTime(String currentTime) {
        return getSunsetTime().equals(currentTime);
    }

    public boolean isBedtime(String currentTime) {
        return getBedtime().equals(currentTime);
    }

    public boolean shouldTriggerSunset() {
        if (!autoScheduleEnabled) return false;
        return isSunsetTime(getCurrentTime());
    }

    public boolean shouldTriggerBedtime() {
        if (!autoScheduleEnabled) return false;
        return isBedtime(getCurrentTime());
    }

    // Utility methods
    public String getBrightnessPercentage() {
        return brightness + "%";
    }

    public String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(Calendar.getInstance().getTime());
    }

    public boolean isColorWarm() {
        return WARM_WHITE.equals(color) || SOFT_YELLOW.equals(color) || SOFT_RED.equals(color);
    }

    public boolean isColorCool() {
        return COOL_WHITE.equals(color) || SOFT_BLUE.equals(color) || PURE_WHITE.equals(color);
    }

    // State validation
    public boolean isValidState() {
        return brightness >= MIN_BRIGHTNESS &&
                brightness <= MAX_BRIGHTNESS &&
                color != null &&
                !color.isEmpty() &&
                bedtime != null &&
                sunsetTime != null &&
                username != null;
    }

    // Color name helper
    public String getColorName() {
        switch (color) {
            case WARM_WHITE: return "Warm White";
            case SOFT_YELLOW: return "Soft Yellow";
            case PURE_WHITE: return "Pure White";
            case COOL_WHITE: return "Cool White";
            case SOFT_BLUE: return "Soft Blue";
            case LAVENDER: return "Lavender";
            case SOFT_RED: return "Soft Red";
            case SOFT_GREEN: return "Soft Green";
            default: return "Custom Color";
        }
    }

    // Schedule summary for UI
    public String getScheduleSummary() {
        if (!autoScheduleEnabled) {
            return "Auto schedule disabled";
        }
        return String.format("Sunset: %s (80%%) • Bedtime: %s (20%%)",
                getSunsetTime(), getBedtime());
    }

    // Copy method for safe cloning
    public LightSettings copy() {
        LightSettings copy = new LightSettings(isOn, brightness, color, bedtime,
                sunsetTime, autoScheduleEnabled, username);
        copy.setBulbOn(bulbOn);
        copy.setDimmed(dimmed);
        copy.setAutoModeEnabled(autoModeEnabled);
        copy.setAmbientLightLevel(ambientLightLevel);
        copy.setStateChanged(stateChanged);
        copy.setPreviousBrightness(previousBrightness);
        return copy;
    }

    // Reset to defaults
    public void resetToDefaults() {
        this.isOn = true;
        this.bulbOn = true;
        this.brightness = 80;
        this.color = SOFT_YELLOW;
        this.bedtime = "22:00";
        this.sunsetTime = "18:30";
        this.autoScheduleEnabled = true;
        this.autoModeEnabled = false;
        this.username = "User";
        this.dimmed = false;
        this.ambientLightLevel = 50;
        this.stateChanged = false;
        this.previousBrightness = 80;
    }

    // Equals and hashCode for proper comparison
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        LightSettings that = (LightSettings) obj;
        return isOn == that.isOn &&
                bulbOn == that.bulbOn &&
                brightness == that.brightness &&
                autoScheduleEnabled == that.autoScheduleEnabled &&
                autoModeEnabled == that.autoModeEnabled &&
                dimmed == that.dimmed &&
                ambientLightLevel == that.ambientLightLevel &&
                getColor().equals(that.getColor()) &&
                getBedtime().equals(that.getBedtime()) &&
                getSunsetTime().equals(that.getSunsetTime()) &&
                getUsername().equals(that.getUsername());
    }

    @Override
    public int hashCode() {
        int result = (isOn ? 1 : 0);
        result = 31 * result + (bulbOn ? 1 : 0);
        result = 31 * result + brightness;
        result = 31 * result + getColor().hashCode();
        result = 31 * result + getBedtime().hashCode();
        result = 31 * result + getSunsetTime().hashCode();
        result = 31 * result + (autoScheduleEnabled ? 1 : 0);
        result = 31 * result + (autoModeEnabled ? 1 : 0);
        result = 31 * result + (dimmed ? 1 : 0);
        result = 31 * result + ambientLightLevel;
        result = 31 * result + getUsername().hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "LightSettings{" +
                "isOn=" + isOn +
                ", bulbOn=" + bulbOn +
                ", brightness=" + brightness + "%" +
                ", color='" + getColorName() + "' (" + color + ")" +
                ", bedtime='" + bedtime + '\'' +
                ", sunsetTime='" + sunsetTime + '\'' +
                ", autoScheduleEnabled=" + autoScheduleEnabled +
                ", autoModeEnabled=" + autoModeEnabled +
                ", dimmed=" + dimmed +
                ", ambientLightLevel=" + ambientLightLevel + "%" +
                ", username='" + username + '\'' +
                '}';
    }

    // JSON-like representation for debugging
    public String toDetailedString() {
        return String.format(
                "Light Settings:\n" +
                        "  State: %s (Bulb: %s)\n" +
                        "  Brightness: %d%% %s\n" +
                        "  Color: %s (%s)\n" +
                        "  Schedule: %s\n" +
                        "  Auto Mode: %s\n" +
                        "  Ambient Light: %d%%\n" +
                        "  User: %s",
                isOn ? "ON" : "OFF",
                bulbOn ? "ON" : "OFF",
                brightness,
                dimmed ? "(DIMMED)" : "",
                getColorName(), color,
                getScheduleSummary(),
                autoModeEnabled ? "Enabled" : "Disabled",
                ambientLightLevel,
                username
        );
    }
}