// TuyaCloudApiService.java - Updated with configurable device ID
package com.smart.smartbulb.services;

import android.graphics.Color;
import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Updated Tuya Cloud API implementation with configurable device ID
 * Device ID is now set through setDeviceId() method instead of being hardcoded
 */
public class TuyaCloudApiService {

    private static final String TAG = "TuyaCloudAPI";

    // Tuya Cloud API Configuration - UPDATE THESE WITH YOUR CREDENTIALS
    private static final String BASE_URL = "https://openapi.tuyaeu.com";
    private static final String CLIENT_ID = "{Client_ID}";     // Your Access ID
    private static final String CLIENT_SECRET = "{client_secret}";    // Your Access Secret

    // Device configuration - Now configurable via setDeviceId()
    private String deviceId = null; // No default device ID - must be set via setDeviceId()

    // Authentication
    private String accessToken = null;
    private long tokenExpireTime = 0;

    // Device state tracking
    private boolean isLightOn = false;
    private int currentBrightness = 100;
    private String currentColor = "#FFFFFF";

    public interface TuyaCloudCallback {
        void onDeviceConnected(String deviceName);
        void onDeviceDisconnected();
        void onBrightnessChanged(int brightness);
        void onLightStateChanged(boolean isOn);
        void onColorChanged(String hexColor);
        void onError(String error);
        void onSuccess(String message);
    }

    private TuyaCloudCallback callback;

    public TuyaCloudApiService() {
        // Constructor - device ID must be set via setDeviceId()
    }

    /**
     * Set callback for device events
     */
    public void setCallback(TuyaCloudCallback callback) {
        this.callback = callback;
    }

    /**
     * Set device ID - REQUIRED before calling connect()
     */
    public void setDeviceId(String deviceId) {
        if (deviceId == null || deviceId.trim().isEmpty()) {
            Log.e(TAG, "Device ID cannot be null or empty");
            return;
        }

        this.deviceId = deviceId.trim();
        Log.d(TAG, "Device ID set: " + this.deviceId);
    }

    /**
     * Get current device ID
     */
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Check if device ID is set
     */
    public boolean isDeviceIdSet() {
        return deviceId != null && !deviceId.trim().isEmpty();
    }

    /**
     * Initialize connection to Tuya Cloud
     */
    public void connect() {
        Log.d(TAG, "Connecting to Tuya Cloud API with configurable device ID...");

        // Validate configuration
        if ("your_tuya_client_id_here".equals(CLIENT_ID) || "your_tuya_secret_here".equals(CLIENT_SECRET)) {
            Log.e(TAG, "Please update CLIENT_ID and CLIENT_SECRET with your actual Tuya credentials");
            if (callback != null) {
                callback.onError("Configuration error: Please set your Tuya credentials");
            }
            return;
        }

        // Validate device ID is set
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Device ID not set. Please call setDeviceId() before connect()");
            if (callback != null) {
                callback.onError("Device ID not configured. Please set device ID first.");
            }
            return;
        }

        Log.d(TAG, "Connecting with device ID: " + deviceId);
        authenticate();
    }

    /**
     * Authenticate with Tuya Cloud to get access token
     * Using CORRECT signature format from Stack Overflow solution
     */
    private void authenticate() {
        new AuthTask().execute();
    }

    /**
     * Turn light on/off
     */
    public void setLightState(boolean isOn) {
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Cannot set light state: Device ID not set");
            if (callback != null) {
                callback.onError("Device ID not configured");
            }
            return;
        }

        Log.d(TAG, "Setting light state: " + (isOn ? "ON" : "OFF") + " for device: " + deviceId);

        JSONObject command = new JSONObject();
        try {
            command.put("code", "switch_led");
            command.put("value", isOn);
            sendCommand(command);
        } catch (Exception e) {
            Log.e(TAG, "Error creating light state command: " + e.getMessage());
        }
    }

    /**
     * Set brightness (1-100%)
     */
    public void setBrightness(int brightness) {
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Cannot set brightness: Device ID not set");
            if (callback != null) {
                callback.onError("Device ID not configured");
            }
            return;
        }

        Log.d(TAG, "Setting brightness: " + brightness + "% for device: " + deviceId);

        // Tuya brightness range is typically 10-1000
        int tuyaBrightness = Math.max(10, Math.min(1000, brightness * 10));

        JSONObject command = new JSONObject();
        try {
            command.put("code", "bright_value_v2");
            command.put("value", tuyaBrightness);
            sendCommand(command);
        } catch (Exception e) {
            Log.e(TAG, "Error creating brightness command: " + e.getMessage());
        }
    }

    /**
     * Set color using hex color code
     */
    public void setColor(String hexColor) {
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Cannot set color: Device ID not set");
            if (callback != null) {
                callback.onError("Device ID not configured");
            }
            return;
        }

        Log.d(TAG, "Setting color: " + hexColor + " for device: " + deviceId);

        try {
            // Remove # if present
            if (hexColor.startsWith("#")) {
                hexColor = hexColor.substring(1);
            }

            // Parse RGB from hex
            int r = Integer.valueOf(hexColor.substring(0, 2), 16);
            int g = Integer.valueOf(hexColor.substring(2, 4), 16);
            int b = Integer.valueOf(hexColor.substring(4, 6), 16);

            // Convert RGB to HSV
            float[] hsv = new float[3];
            Color.RGBToHSV(r, g, b, hsv);

            // Convert to Tuya's scale - based on your device status format
            int h = (int) hsv[0];                    // Hue: 0-360 (same as your device shows)
            int s = (int) (hsv[1] * 1000);           // Saturation: 0-1000 (same as your device shows)
            int v = (int) (hsv[2] * 1000);           // Value: 0-1000 (same as your device shows)

            // Create JSON string in the exact format your device expects
            String hsvJson = String.format("{\"h\":%d,\"s\":%d,\"v\":%d}", h, s, v);

            Log.d(TAG, "Converted " + hexColor + " to HSV JSON: " + hsvJson);

            JSONObject command = new JSONObject();
            command.put("code", "colour_data_v2");
            command.put("value", hsvJson);
            sendCommand(command);

        } catch (Exception e) {
            Log.e(TAG, "Error creating color command: " + e.getMessage());
            if (callback != null) {
                callback.onError("Failed to set color: " + e.getMessage());
            }
        }
    }

    /**
     * Get current device status
     */
    public void getDeviceStatus() {
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Cannot get device status: Device ID not set");
            if (callback != null) {
                callback.onError("Device ID not configured");
            }
            return;
        }

        Log.d(TAG, "Getting device status for device: " + deviceId);
        new GetStatusTask().execute();
    }

    /**
     * Send command to device
     */
    private void sendCommand(JSONObject command) {
        if (!isDeviceIdSet()) {
            Log.e(TAG, "Cannot send command: Device ID not set");
            if (callback != null) {
                callback.onError("Device ID not configured");
            }
            return;
        }

        new SendCommandTask(command).execute();
    }

    /**
     * Check if device is connected (has valid token and device ID is set)
     */
    public boolean isConnected() {
        return isDeviceIdSet() && accessToken != null && System.currentTimeMillis() < tokenExpireTime;
    }

    /**
     * Get current device state
     */
    public boolean isLightOn() {
        return isLightOn;
    }

    public int getCurrentBrightness() {
        return currentBrightness;
    }

    public String getCurrentColor() {
        return currentColor;
    }

    /**
     * Disconnect from Tuya Cloud
     */
    public void disconnect() {
        Log.d(TAG, "Disconnecting from Tuya Cloud" + (deviceId != null ? " (device: " + deviceId + ")" : ""));
        accessToken = null;
        tokenExpireTime = 0;

        if (callback != null) {
            callback.onDeviceDisconnected();
        }
    }

    /**
     * Clear device configuration (useful when switching devices)
     */
    public void clearDeviceId() {
        Log.d(TAG, "Clearing device ID configuration");
        deviceId = null;
        // Also disconnect to ensure clean state
        disconnect();
    }

    // ==================== ASYNC TASKS ====================

    /**
     * Authentication task with CORRECT Tuya signature format
     */
    private class AuthTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            try {
                String timestamp = String.valueOf(System.currentTimeMillis());
                String url = BASE_URL + "/v1.0/token?grant_type=1";
                String signUrl = "/v1.0/token?grant_type=1";

                Log.d(TAG, "=== AUTHENTICATION WITH CORRECT SIGNATURE ===");
                Log.d(TAG, "Timestamp: " + timestamp);
                Log.d(TAG, "Client ID: " + CLIENT_ID);
                Log.d(TAG, "URL: " + url);

                // CORRECT signature format based on Stack Overflow solution:
                // 1. Create content hash of empty body (for GET request)
                String contentHash = createSHA256Hash("");

                // 2. Create string to sign: METHOD + "\n" + contentHash + "\n" + "" + "\n" + signUrl
                String method = "GET";
                String stringToSign = method + "\n" + contentHash + "\n" + "\n" + signUrl;

                // 3. Create signature string: CLIENT_ID + timestamp + stringToSign
                String signStr = CLIENT_ID + timestamp + stringToSign;

                // 4. Create HMAC-SHA256 signature
                String signature = createHMACSignature(signStr, CLIENT_SECRET);

                Log.d(TAG, "Content hash: " + contentHash);
                Log.d(TAG, "String to sign: " + stringToSign.replace("\n", "\\n"));
                Log.d(TAG, "Sign string: " + signStr.replace("\n", "\\n"));
                Log.d(TAG, "Generated signature: " + signature);

                // Make HTTP request with correct headers
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("client_id", CLIENT_ID);
                conn.setRequestProperty("sign", signature);
                conn.setRequestProperty("t", timestamp);
                conn.setRequestProperty("sign_method", "HMAC-SHA256");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Auth response code: " + responseCode);

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    Log.d(TAG, "Auth response: " + response);

                    JSONObject jsonResponse = new JSONObject(response);
                    if (jsonResponse.getBoolean("success")) {
                        JSONObject result = jsonResponse.getJSONObject("result");
                        accessToken = result.getString("access_token");
                        int expireTime = result.getInt("expire_time");
                        tokenExpireTime = System.currentTimeMillis() + (expireTime * 1000L);

                        Log.d(TAG, "🎉 Authentication successful! Token expires in " + expireTime + " seconds");
                        return "SUCCESS";
                    } else {
                        String msg = jsonResponse.optString("msg", "Unknown error");
                        int code = jsonResponse.optInt("code", -1);
                        Log.e(TAG, "Auth failed - Code: " + code + ", Message: " + msg);
                        return "AUTH_FAILED: " + msg + " (Code: " + code + ")";
                    }
                } else {
                    String errorResponse = readErrorResponse(conn);
                    Log.e(TAG, "Auth HTTP error " + responseCode + ": " + errorResponse);
                    return "HTTP_ERROR: " + responseCode + " - " + errorResponse;
                }

            } catch (Exception e) {
                Log.e(TAG, "Auth exception: " + e.getMessage());
                e.printStackTrace();
                return "EXCEPTION: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result.equals("SUCCESS")) {
                Log.d(TAG, "Connected to Tuya Cloud successfully for device: " + deviceId);
                if (callback != null) {
                    callback.onDeviceConnected("Tuya Smart Bulb (" + deviceId.substring(0, 8) + "...)");
                }
                // Get initial device status
                getDeviceStatus();
            } else {
                Log.e(TAG, "Failed to connect: " + result);
                if (callback != null) {
                    callback.onError("Connection failed: " + result);
                }
            }
        }
    }

    /**
     * Send command task with CORRECT signature format
     */
    private class SendCommandTask extends AsyncTask<Void, Void, String> {
        private JSONObject command;

        public SendCommandTask(JSONObject command) {
            this.command = command;
        }

        @Override
        protected String doInBackground(Void... voids) {
            try {
                // Check if token is valid and device ID is set
                if (!isConnected()) {
                    Log.w(TAG, "Token expired or device ID not set, re-authenticating...");
                    return "TOKEN_EXPIRED";
                }

                String timestamp = String.valueOf(System.currentTimeMillis());
                String url = BASE_URL + "/v1.0/devices/" + deviceId + "/commands";
                String path = "/v1.0/devices/" + deviceId + "/commands";

                // Create request body
                JSONObject requestBody = new JSONObject();
                JSONArray commands = new JSONArray();
                commands.put(command);
                requestBody.put("commands", commands);

                String body = requestBody.toString();
                Log.d(TAG, "Sending command to device " + deviceId + ": " + body);

                // CORRECT signature format for POST with body:
                // 1. Create content hash of body
                String contentHash = createSHA256Hash(body);

                // 2. Create string to sign: METHOD + "\n" + contentHash + "\n" + "" + "\n" + path
                String method = "POST";
                String stringToSign = method + "\n" + contentHash + "\n" + "\n" + path;

                // 3. Create signature string: CLIENT_ID + accessToken + timestamp + stringToSign
                String signStr = CLIENT_ID + accessToken + timestamp + stringToSign;

                // 4. Create HMAC-SHA256 signature
                String signature = createHMACSignature(signStr, CLIENT_SECRET);

                Log.d(TAG, "Command signature details:");
                Log.d(TAG, "  Content hash: " + contentHash);
                Log.d(TAG, "  String to sign: " + stringToSign.replace("\n", "\\n"));
                Log.d(TAG, "  Sign string: " + signStr.replace("\n", "\\n"));
                Log.d(TAG, "  Signature: " + signature);

                // Make HTTP request
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("client_id", CLIENT_ID);
                conn.setRequestProperty("access_token", accessToken);
                conn.setRequestProperty("sign", signature);
                conn.setRequestProperty("t", timestamp);
                conn.setRequestProperty("sign_method", "HMAC-SHA256");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // Send request body
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Command response code: " + responseCode);

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    Log.d(TAG, "Command response: " + response);

                    JSONObject jsonResponse = new JSONObject(response);
                    if (jsonResponse.getBoolean("success")) {
                        return "SUCCESS";
                    } else {
                        String msg = jsonResponse.optString("msg", "Command failed");
                        int code = jsonResponse.optInt("code", -1);
                        return "COMMAND_FAILED: " + msg + " (Code: " + code + ")";
                    }
                } else {
                    String errorResponse = readErrorResponse(conn);
                    return "HTTP_ERROR: " + responseCode + " - " + errorResponse;
                }

            } catch (Exception e) {
                Log.e(TAG, "Command exception: " + e.getMessage());
                e.printStackTrace();
                return "EXCEPTION: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result.equals("SUCCESS")) {
                Log.d(TAG, "Command sent successfully to device: " + deviceId);
                if (callback != null) {
                    callback.onSuccess("Command executed successfully");
                }
                // Get updated device status
                new android.os.Handler().postDelayed(() -> getDeviceStatus(), 1000);
            } else if (result.equals("TOKEN_EXPIRED")) {
                // Re-authenticate and retry
                authenticate();
            } else {
                Log.e(TAG, "Command failed for device " + deviceId + ": " + result);
                if (callback != null) {
                    callback.onError("Command failed: " + result);
                }
            }
        }
    }

    /**
     * Get device status task with CORRECT signature format
     */
    private class GetStatusTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            try {
                // Check if token is valid and device ID is set
                if (!isConnected()) {
                    return "TOKEN_EXPIRED";
                }

                String timestamp = String.valueOf(System.currentTimeMillis());
                String url = BASE_URL + "/v1.0/devices/" + deviceId + "/status";
                String path = "/v1.0/devices/" + deviceId + "/status";

                // CORRECT signature format for GET without body:
                // 1. Create content hash of empty body
                String contentHash = createSHA256Hash("");

                // 2. Create string to sign: METHOD + "\n" + contentHash + "\n" + "" + "\n" + path
                String method = "GET";
                String stringToSign = method + "\n" + contentHash + "\n" + "\n" + path;

                // 3. Create signature string: CLIENT_ID + accessToken + timestamp + stringToSign
                String signStr = CLIENT_ID + accessToken + timestamp + stringToSign;

                // 4. Create HMAC-SHA256 signature
                String signature = createHMACSignature(signStr, CLIENT_SECRET);

                // Make HTTP request
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("client_id", CLIENT_ID);
                conn.setRequestProperty("access_token", accessToken);
                conn.setRequestProperty("sign", signature);
                conn.setRequestProperty("t", timestamp);
                conn.setRequestProperty("sign_method", "HMAC-SHA256");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int responseCode = conn.getResponseCode();

                if (responseCode == 200) {
                    String response = readResponse(conn);
                    Log.d(TAG, "Status response for device " + deviceId + ": " + response);

                    JSONObject jsonResponse = new JSONObject(response);
                    if (jsonResponse.getBoolean("success")) {
                        JSONArray result = jsonResponse.getJSONArray("result");
                        parseDeviceStatus(result);
                        return "SUCCESS";
                    } else {
                        String msg = jsonResponse.optString("msg", "Failed to get status");
                        int code = jsonResponse.optInt("code", -1);
                        return "STATUS_FAILED: " + msg + " (Code: " + code + ")";
                    }
                } else {
                    String errorResponse = readErrorResponse(conn);
                    return "HTTP_ERROR: " + responseCode + " - " + errorResponse;
                }

            } catch (Exception e) {
                Log.e(TAG, "Status exception: " + e.getMessage());
                e.printStackTrace();
                return "EXCEPTION: " + e.getMessage();
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result.equals("TOKEN_EXPIRED")) {
                authenticate();
            } else if (!result.equals("SUCCESS")) {
                Log.e(TAG, "Get status failed for device " + deviceId + ": " + result);
            }
        }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Parse device status from API response
     */
    private void parseDeviceStatus(JSONArray statusArray) {
        try {
            for (int i = 0; i < statusArray.length(); i++) {
                JSONObject status = statusArray.getJSONObject(i);
                String code = status.getString("code");

                switch (code) {
                    case "switch_led":
                        boolean newLightState = status.getBoolean("value");
                        if (newLightState != isLightOn) {
                            isLightOn = newLightState;
                            Log.d(TAG, "Light state changed for device " + deviceId + ": " + (isLightOn ? "ON" : "OFF"));
                            if (callback != null) {
                                callback.onLightStateChanged(isLightOn);
                            }
                        }
                        break;

                    case "bright_value_v2":
                        int tuyaBrightness = status.getInt("value");
                        int newBrightness = Math.max(1, Math.min(100, tuyaBrightness / 10));
                        if (newBrightness != currentBrightness) {
                            currentBrightness = newBrightness;
                            Log.d(TAG, "Brightness changed for device " + deviceId + ": " + currentBrightness + "%");
                            if (callback != null) {
                                callback.onBrightnessChanged(currentBrightness);
                            }
                        }
                        break;

                    case "colour_data_v2":
                        String hsvValue = status.getString("value");
                        String newColor = convertHSVToHex(hsvValue);
                        if (!newColor.equals(currentColor)) {
                            currentColor = newColor;
                            Log.d(TAG, "Color changed for device " + deviceId + ": " + currentColor);
                            if (callback != null) {
                                callback.onColorChanged(currentColor);
                            }
                        }
                        break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing device status: " + e.getMessage());
        }
    }

    /**
     * Convert HSV color from Tuya to hex
     */
    private String convertHSVToHex(String hsvColor) {
        try {
            // Check if it's the new JSON format
            if (hsvColor.startsWith("{") && hsvColor.contains("\"h\":")) {
                JSONObject hsvJson = new JSONObject(hsvColor);
                int h = hsvJson.getInt("h");
                int s = hsvJson.getInt("s");
                int v = hsvJson.getInt("v");

                // Convert from Tuya scale to Android scale
                float[] hsv = {h, s / 1000f, v / 1000f};
                int color = Color.HSVToColor(hsv);

                return String.format("#%06X", (0xFFFFFF & color));
            }
            // Handle old hex format (12 characters)
            else if (hsvColor.length() == 12) {
                int h = Integer.parseInt(hsvColor.substring(0, 4), 16);
                int s = Integer.parseInt(hsvColor.substring(4, 8), 16);
                int v = Integer.parseInt(hsvColor.substring(8, 12), 16);

                float[] hsv = {(h * 360f) / 65535f, s / 1000f, v / 1000f};
                int color = Color.HSVToColor(hsv);

                return String.format("#%06X", (0xFFFFFF & color));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error converting HSV to hex: " + e.getMessage());
        }

        return "#FFFFFF"; // Default to white
    }

    /**
     * Create SHA256 hash (required for Tuya signature)
     */
    private String createSHA256Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error creating SHA256 hash: " + e.getMessage());
            return "";
        }
    }

    /**
     * Create HMAC-SHA256 signature (uppercase hex as required by Tuya)
     */
    private String createHMACSignature(String message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder();
            for (byte b : hash) {
                result.append(String.format("%02X", b));
            }
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error creating HMAC signature: " + e.getMessage());
            return "";
        }
    }

    /**
     * Read response from HTTP connection
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    /**
     * Read error response from HTTP connection
     */
    private String readErrorResponse(HttpURLConnection conn) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();
            return response.toString();
        } catch (Exception e) {
            return "Could not read error response";
        }
    }
}