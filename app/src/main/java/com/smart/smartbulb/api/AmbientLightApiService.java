// AmbientLightApiService.java (With Authentication)
package com.smart.smartbulb.api;

import android.os.AsyncTask;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class AmbientLightApiService {
    private static final String TAG = "AmbientLightAPI";

    // ThingsBoard Demo Server Configuration
    private static final String BASE_URL = "https://demo.thingsboard.io";
    private static final String LOGIN_URL = BASE_URL + "/api/auth/login";
    private static final String TELEMETRY_URL = BASE_URL + "/api/plugins/telemetry/DEVICE/07e5c2d0-319d-11f0-905b-715188ad2cd8/values/timeseries";

    // Demo credentials (these are public demo credentials)
    private static final String DEMO_USERNAME = "{username}";
    private static final String DEMO_PASSWORD = "{password}";

    // Authentication token (cached)
    private static String authToken = null;
    private static long tokenExpiryTime = 0;

    public interface AmbientLightCallback {
        void onSuccess(AmbientLightData data);
        void onError(String error);
    }

    public static class AmbientLightData {
        private final double lux;
        private final double percentage;
        private final double rms;
        private final long timestamp;
        private final String formattedTime;

        public AmbientLightData(double lux, double percentage, double rms, long timestamp, String formattedTime) {
            this.lux = lux;
            this.percentage = percentage;
            this.rms = rms;
            this.timestamp = timestamp;
            this.formattedTime = formattedTime;
        }

        public double getLux() { return lux; }
        public double getPercentage() { return percentage; }
        public double getRms() { return rms; }
        public long getTimestamp() { return timestamp; }
        public String getFormattedTime() { return formattedTime; }

        // Convert percentage to 0-100 range for progress bar
        public int getPercentageAsInt() {
            return (int) Math.round(Math.max(0, Math.min(100, percentage)));
        }

        // Get lux level as a descriptive string
        public String getLuxDescription() {
            if (lux < 10) return "Very Dark";
            else if (lux < 50) return "Dark";
            else if (lux < 200) return "Dim";
            else if (lux < 500) return "Normal";
            else if (lux < 1000) return "Bright";
            else return "Very Bright";
        }
    }

    public static void fetchAmbientLightData(AmbientLightCallback callback) {
        new FetchAmbientLightTask(callback).execute();
    }

    private static class FetchAmbientLightTask extends AsyncTask<Void, Void, AmbientLightData> {
        private final AmbientLightCallback callback;
        private String errorMessage;

        public FetchAmbientLightTask(AmbientLightCallback callback) {
            this.callback = callback;
        }

        @Override
        protected AmbientLightData doInBackground(Void... voids) {
            try {
                // Check if we need to authenticate or refresh token
                if (authToken == null || System.currentTimeMillis() > tokenExpiryTime) {
                    Log.d(TAG, "Authenticating with ThingsBoard...");
                    if (!authenticate()) {
                        return null;
                    }
                }

                // Fetch telemetry data with authentication
                return fetchTelemetryData();

            } catch (Exception e) {
                Log.e(TAG, "Error in background task: " + e.getMessage());
                errorMessage = "Unexpected error: " + e.getMessage();
                return null;
            }
        }

        private boolean authenticate() {
            try {
                URL url = new URL(LOGIN_URL);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                setupTrustAllCerts(connection);

                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                // Create login JSON
                JSONObject loginJson = new JSONObject();
                loginJson.put("username", DEMO_USERNAME);
                loginJson.put("password", DEMO_PASSWORD);

                // Send login request
                OutputStream os = connection.getOutputStream();
                os.write(loginJson.toString().getBytes());
                os.flush();
                os.close();

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Login response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // Parse token from response
                    JSONObject responseJson = new JSONObject(response.toString());
                    authToken = responseJson.getString("token");

                    // Set token expiry (ThingsBoard tokens typically last 2.5 hours)
                    tokenExpiryTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000); // 2 hours

                    Log.d(TAG, "Authentication successful, token obtained");
                    return true;

                } else {
                    // Read error response
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponse.append(errorLine);
                    }
                    errorReader.close();

                    errorMessage = "Authentication failed: " + errorResponse.toString();
                    Log.e(TAG, errorMessage);
                    return false;
                }

            } catch (IOException e) {
                errorMessage = "Network error during authentication: " + e.getMessage();
                Log.e(TAG, errorMessage);
                return false;
            } catch (JSONException e) {
                errorMessage = "JSON error during authentication: " + e.getMessage();
                Log.e(TAG, errorMessage);
                return false;
            }
        }

        private AmbientLightData fetchTelemetryData() {
            try {
                URL url = new URL(TELEMETRY_URL);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                setupTrustAllCerts(connection);

                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("X-Authorization", "Bearer " + authToken);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Telemetry response code: " + responseCode);

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String responseData = response.toString();
                    Log.d(TAG, "Successfully fetched telemetry data");
                    Log.d(TAG, "Response data: " + responseData);

                    return parseResponse(responseData);

                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    // Token might be expired, clear it and try once more
                    authToken = null;
                    tokenExpiryTime = 0;
                    errorMessage = "Authentication token expired, please try again";
                    Log.w(TAG, errorMessage);
                    return null;

                } else {
                    // Read error response
                    BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String errorLine;
                    while ((errorLine = errorReader.readLine()) != null) {
                        errorResponse.append(errorLine);
                    }
                    errorReader.close();

                    errorMessage = "HTTP Error " + responseCode + ": " + errorResponse.toString();
                    Log.e(TAG, errorMessage);
                    return null;
                }

            } catch (IOException e) {
                errorMessage = "Network error fetching telemetry: " + e.getMessage();
                Log.e(TAG, errorMessage);
                return null;
            } catch (JSONException e) {
                errorMessage = "JSON parsing error: " + e.getMessage();
                Log.e(TAG, errorMessage);
                return null;
            }
        }

        /**
         * Setup to accept all SSL certificates (for demo purposes only)
         */
        private void setupTrustAllCerts(HttpsURLConnection connection) {
            try {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return null;
                            }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            }
                            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
                            }
                        }
                };

                SSLContext sc = SSLContext.getInstance("SSL");
                sc.init(null, trustAllCerts, new java.security.SecureRandom());
                connection.setSSLSocketFactory(sc.getSocketFactory());
                connection.setHostnameVerifier((hostname, session) -> true);

            } catch (Exception e) {
                Log.w(TAG, "Failed to setup SSL trust: " + e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(AmbientLightData result) {
            if (result != null && callback != null) {
                callback.onSuccess(result);
            } else if (callback != null) {
                String finalError = errorMessage != null ? errorMessage : "Failed to fetch telemetry data";
                callback.onError(finalError);
            }
        }

        private AmbientLightData parseResponse(String jsonResponse) throws JSONException {
            Log.d(TAG, "Parsing JSON response: " + jsonResponse);

            JSONObject jsonObject = new JSONObject(jsonResponse);

            // Extract lux data
            double lux = 0;
            if (jsonObject.has("lux")) {
                JSONArray luxArray = jsonObject.getJSONArray("lux");
                if (luxArray.length() > 0) {
                    JSONObject luxObj = luxArray.getJSONObject(0);
                    lux = Double.parseDouble(luxObj.getString("value"));
                    Log.d(TAG, "Parsed lux: " + lux);
                }
            }

            // Extract percentage data
            double percentage = 0;
            if (jsonObject.has("percentage")) {
                JSONArray percentageArray = jsonObject.getJSONArray("percentage");
                if (percentageArray.length() > 0) {
                    JSONObject percentageObj = percentageArray.getJSONObject(0);
                    percentage = Double.parseDouble(percentageObj.getString("value"));
                    Log.d(TAG, "Parsed percentage: " + percentage);
                }
            }

            // Extract RMS data
            double rms = 0;
            if (jsonObject.has("rms")) {
                JSONArray rmsArray = jsonObject.getJSONArray("rms");
                if (rmsArray.length() > 0) {
                    JSONObject rmsObj = rmsArray.getJSONObject(0);
                    rms = Double.parseDouble(rmsObj.getString("value"));
                    Log.d(TAG, "Parsed RMS: " + rms);
                }
            }

            // Extract timestamp and formatted time
            long timestamp = System.currentTimeMillis(); // Default to current time
            String formattedTime = "";
            if (jsonObject.has("time")) {
                JSONArray timeArray = jsonObject.getJSONArray("time");
                if (timeArray.length() > 0) {
                    JSONObject timeObj = timeArray.getJSONObject(0);
                    timestamp = timeObj.getLong("ts");
                    formattedTime = timeObj.getString("value");
                    Log.d(TAG, "Parsed timestamp: " + timestamp + ", formatted: " + formattedTime);
                }
            }

            Log.d(TAG, String.format("Final parsed data - Lux: %.2f, Percentage: %.2f, RMS: %.2f", lux, percentage, rms));

            return new AmbientLightData(lux, percentage, rms, timestamp, formattedTime);
        }
    }

    /**
     * Clear cached authentication token (useful for logout or token refresh)
     */
    public static void clearAuthToken() {
        authToken = null;
        tokenExpiryTime = 0;
        Log.d(TAG, "Authentication token cleared");
    }

    /**
     * Check if we have a valid authentication token
     */
    public static boolean isAuthenticated() {
        return authToken != null && System.currentTimeMillis() < tokenExpiryTime;
    }
}