package com.smart.smartbulb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.smart.smartbulb.fragments.HomeFragment;
import com.smart.smartbulb.fragments.NotificationsFragment;
import com.smart.smartbulb.fragments.SettingsFragment;
import com.smart.smartbulb.fragments.VoiceControlFragment;
import com.smart.smartbulb.models.LightSettings;
import com.smart.smartbulb.models.Notification;
import com.smart.smartbulb.services.BackgroundScheduleService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;



// Updated MainActivity.java with Background Service Integration
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private BottomNavigationView bottomNavigation;
    private LightSettings lightSettings;
    private List<Notification> notifications;
    private Chip userChip;
    private volatile boolean isActivityActive = false;

    // Background service integration
    private BackgroundScheduleService backgroundService;
    private ServiceConnection serviceConnection;
    private boolean isServiceBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "MainActivity created with background service integration");
        isActivityActive = true;

        // Initialize app data
        lightSettings = new LightSettings();
        notifications = new ArrayList<>();

        // Setup UI components
        initializeViews();
        setupBottomNavigation();

        // Load home fragment by default
        if (savedInstanceState == null) {
            safeLoadFragment(new HomeFragment());
        }

        // Start and bind to background service
        startBackgroundService();
        bindToBackgroundService();
    }

    private void initializeViews() {
        try {
            userChip = findViewById(R.id.userChip);
            bottomNavigation = findViewById(R.id.bottomNavigation);

            if (userChip != null) {
                updateUserChip();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing views", e);
        }
    }

    private void updateUserChip() {
        if (userChip != null && lightSettings != null && !isFinishing()) {
            runOnUiThread(() -> {
                try {
                    userChip.setText(lightSettings.getUsername());
                } catch (Exception e) {
                    Log.e(TAG, "Error updating user chip", e);
                }
            });
        }
    }

    private void setupBottomNavigation() {
        if (bottomNavigation == null) {
            Log.e(TAG, "Bottom navigation is null");
            return;
        }

        bottomNavigation.setOnItemSelectedListener(item -> {
            if (isFinishing() || !isActivityActive) {
                return false;
            }

            Fragment fragment = null;
            int itemId = item.getItemId();

            try {
                if (itemId == R.id.nav_home) {
                    fragment = new HomeFragment();
                } else if (itemId == R.id.nav_notifications) {
                    fragment = new NotificationsFragment();
                } else if (itemId == R.id.nav_settings) {
                    fragment = new SettingsFragment();
                }else if(itemId == R.id.nav_voice){
                    fragment = new VoiceControlFragment();
                }

                return fragment != null && safeLoadFragment(fragment);
            } catch (Exception e) {
                Log.e(TAG, "Error in navigation selection", e);
                return false;
            }
        });
    }

    private boolean safeLoadFragment(Fragment fragment) {
        if (fragment == null || isFinishing() || !isActivityActive) {
            Log.w(TAG, "Cannot load fragment - activity not ready");
            return false;
        }

        try {
            Bundle bundle = new Bundle();

            // Get latest settings from background service if available
            if (isServiceBound && backgroundService != null) {
                LightSettings serviceSettings = backgroundService.getCurrentSettings();
                if (serviceSettings != null) {
                    lightSettings = serviceSettings;
                }
            }

            // Pass data to fragments with null checks
            if (fragment instanceof HomeFragment) {
                bundle.putSerializable("lightSettings", lightSettings);
                ((HomeFragment) fragment).setCallback(this::updateLightSettings);
            } else if (fragment instanceof NotificationsFragment) {
                bundle.putSerializable("notifications", new ArrayList<>(notifications));
                ((NotificationsFragment) fragment).setDismissCallback(this::dismissNotification);
            } else if (fragment instanceof SettingsFragment) {
                bundle.putSerializable("lightSettings", lightSettings);
                ((SettingsFragment) fragment).setCallback(this::updateLightSettings);
            }

            fragment.setArguments(bundle);

            FragmentManager fragmentManager = getSupportFragmentManager();
            if (fragmentManager.isStateSaved()) {
                Log.w(TAG, "FragmentManager state already saved, deferring transaction");
                return false;
            }

            FragmentTransaction transaction = fragmentManager.beginTransaction();
            transaction.replace(R.id.fragment_container, fragment);
            transaction.commitAllowingStateLoss();

            Log.d(TAG, "Fragment loaded successfully: " + fragment.getClass().getSimpleName());
            return true;

        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot perform fragment transaction - activity state", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error loading fragment", e);
            return false;
        }
    }

    private void startBackgroundService() {
        try {
            Intent serviceIntent = new Intent(this, BackgroundScheduleService.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Log.d(TAG, "Background schedule service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting background service", e);
        }
    }

    private void bindToBackgroundService() {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "Bound to background service");

                BackgroundScheduleService.ScheduleServiceBinder binder =
                        (BackgroundScheduleService.ScheduleServiceBinder) service;
                backgroundService = binder.getService();
                isServiceBound = true;

                // Sync settings with service
                if (backgroundService != null) {
                    LightSettings serviceSettings = backgroundService.getCurrentSettings();
                    if (serviceSettings != null) {
                        lightSettings = serviceSettings;
                        refreshCurrentFragment();
                        updateUserChip();
                    }
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "Disconnected from background service");
                backgroundService = null;
                isServiceBound = false;
            }
        };

        try {
            Intent serviceIntent = new Intent(this, BackgroundScheduleService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Error binding to background service", e);
        }
    }

    public void updateLightSettings(LightSettings settings) {
        if (settings == null || !isActivityActive) {
            Log.w(TAG, "Cannot update light settings - invalid state");
            return;
        }

        try {
            this.lightSettings = settings;
            Log.d(TAG, "Light settings updated: brightness=" + settings.getBrightness() +
                    "%, bulbOn=" + settings.isBulbOn());

            // Update background service with new settings
            if (isServiceBound && backgroundService != null) {
                backgroundService.updateSettings(settings);
            }

            // Update username on change
            updateUserChip();

            // Refresh current fragment to show updated settings
            refreshCurrentFragment();

            // Check if any notification should be sent
            checkForNotificationEvents();

        } catch (Exception e) {
            Log.e(TAG, "Error updating light settings", e);
        }
    }

    private void refreshCurrentFragment() {
        if (isFinishing() || !isActivityActive) return;

        try {
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

            if (currentFragment instanceof HomeFragment) {
                Bundle bundle = new Bundle();
                bundle.putSerializable("lightSettings", lightSettings);
                currentFragment.setArguments(bundle);
                ((HomeFragment) currentFragment).refreshData(lightSettings);
            } else if (currentFragment instanceof SettingsFragment) {
                ((SettingsFragment) currentFragment).refreshSettings(lightSettings);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing current fragment", e);
        }
    }

    private void dismissNotification(int notificationId) {
        if (!isActivityActive) return;

        try {
            notifications.removeIf(notification -> notification.getId() == notificationId);
            Log.d(TAG, "Notification dismissed: " + notificationId);

            // Refresh notifications fragment if it's active
            Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (currentFragment instanceof NotificationsFragment) {
                ((NotificationsFragment) currentFragment).refreshNotifications(notifications);
            }

            updateNotificationBadge();
        } catch (Exception e) {
            Log.e(TAG, "Error dismissing notification", e);
        }
    }

    private void updateNotificationBadge() {
        if (!isActivityActive || bottomNavigation == null) return;

        runOnUiThread(() -> {
            try {
                if (notifications.size() > 0) {
                    BadgeDrawable badge = bottomNavigation.getOrCreateBadge(R.id.nav_notifications);
                    badge.setNumber(notifications.size());
                    badge.setVisible(true);
                } else {
                    bottomNavigation.removeBadge(R.id.nav_notifications);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error updating notification badge", e);
            }
        });
    }

    private void addNotification(String message) {
        if (!isActivityActive || message == null) return;

        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String currentTime = sdf.format(new Date());

            Notification notification = new Notification();
            notification.setId(System.currentTimeMillis());
            notification.setMessage(message);
            notification.setTime(currentTime);

            notifications.add(0, notification); // Add to the beginning of the list
            Log.d(TAG, "Notification added: " + message);

            // Update badge
            updateNotificationBadge();

            // Show toast for the notification
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error adding notification", e);
        }
    }

    private void checkForNotificationEvents() {
        if (!isActivityActive || lightSettings == null) return;

        try {
            if (lightSettings.hasStateChanged()) {
                // If brightness was changed to 20% from higher value (bedtime)
                if (lightSettings.getBrightness() == 20 &&
                        lightSettings.getPreviousBrightness() > 20) {
                    lightSettings.setDimmed(true);
                    addNotification("🌙 " + getString(R.string.notification_dimming));
                }

                // If brightness was changed to 80% from lower value (sunset or morning)
                if (lightSettings.getBrightness() == 80 &&
                        lightSettings.getPreviousBrightness() < 80) {
                    lightSettings.setDimmed(false);
                    addNotification("🌅 Lights adjusted to evening brightness (80%)");
                }

                // If brightness was changed to 100% (full brightness)
                if (lightSettings.getBrightness() == 100 &&
                        lightSettings.getPreviousBrightness() < 100) {
                    lightSettings.setDimmed(false);
                    addNotification("💡 Lights set to maximum brightness");
                }

                // Reset state change flag
                lightSettings.setStateChanged(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking notification events", e);
        }
    }

    // Public methods for service communication
    public boolean isBackgroundServiceConnected() {
        return isServiceBound && backgroundService != null;
    }

    public void requestServiceReconnect() {
        if (isServiceBound && backgroundService != null) {
            backgroundService.reconnectTuya();
        }
    }

    public String getServiceStatus() {
        if (isServiceBound && backgroundService != null) {
            boolean tuyaConnected = backgroundService.isTuyaConnected();
            return "Background service: " + (tuyaConnected ? "Connected" : "Disconnected");
        }
        return "Background service: Not bound";
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "MainActivity resumed");
        isActivityActive = true;

        // Rebind to service if needed
        if (!isServiceBound) {
            bindToBackgroundService();
        }

        // Sync with background service
        if (isServiceBound && backgroundService != null) {
            LightSettings serviceSettings = backgroundService.getCurrentSettings();
            if (serviceSettings != null) {
                lightSettings = serviceSettings;
                refreshCurrentFragment();
                updateUserChip();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "MainActivity paused");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d(TAG, "MainActivity stopped");
        isActivityActive = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MainActivity destroyed");

        // Mark activity as inactive
        isActivityActive = false;

        // Unbind from service
        if (isServiceBound && serviceConnection != null) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding from service", e);
            }
        }

        // Clear references
        lightSettings = null;
        if (notifications != null) {
            notifications.clear();
            notifications = null;
        }

        userChip = null;
        bottomNavigation = null;
        backgroundService = null;
        serviceConnection = null;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save current state
        if (lightSettings != null) {
            outState.putSerializable("lightSettings", lightSettings);
        }
        if (notifications != null) {
            outState.putSerializable("notifications", new ArrayList<>(notifications));
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // Restore state
        try {
            Object lightSettingsObj = savedInstanceState.getSerializable("lightSettings");
            if (lightSettingsObj instanceof LightSettings) {
                lightSettings = (LightSettings) lightSettingsObj;
            }

            Object notificationsObj = savedInstanceState.getSerializable("notifications");
            if (notificationsObj instanceof ArrayList) {
                notifications = (ArrayList<Notification>) notificationsObj;
                updateNotificationBadge();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restoring instance state", e);
        }
    }
}