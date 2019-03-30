package com.desuuuu.ovrphonebridge;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class NotificationService extends NotificationListenerService implements SharedPreferences.OnSharedPreferenceChangeListener {
    private SharedPreferences mSharedPreferences;

    private LocalBroadcastManager mBroadcastManager;
    private NotificationListRequestReceiver mNotificationListRequestReceiver;
    private NotificationDismissReceiver mNotificationDismissReceiver;

    private boolean mConnected;
    private Set<String> mExcludedApplications;
    private String mLineSeparator;

    private static final String ELLIPSIS = "…";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (action != null && action.equals(Constants.INTENT.START_NOTIFICATION_SERVICE)) {
                if (!mConnected) {
                    restartNotificationListener();
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        mSharedPreferences = getSharedPreferences(Constants.PREFERENCES_NAME, Context.MODE_PRIVATE);

        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);

        mBroadcastManager = LocalBroadcastManager.getInstance(this);

        mNotificationListRequestReceiver = new NotificationListRequestReceiver();
        mNotificationDismissReceiver = new NotificationDismissReceiver();

        mBroadcastManager.registerReceiver(
                mNotificationListRequestReceiver,
                new IntentFilter(Constants.INTENT.NOTIFICATION_LIST_REQUEST));

        mBroadcastManager.registerReceiver(
                mNotificationDismissReceiver,
                new IntentFilter(Constants.INTENT.NOTIFICATION_DISMISS));

        mExcludedApplications = mSharedPreferences.getStringSet("notifications_excluded_applications", new HashSet<>());

        mLineSeparator = System.getProperty("line.separator");

        if (mLineSeparator == null) {
            mLineSeparator = "\n";
        }

        mConnected = true;
    }

    @Override
    public void onListenerDisconnected() {
        mConnected = false;

        if (mSharedPreferences != null) {
            mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);

            mSharedPreferences = null;
        }

        if (mBroadcastManager != null) {
            if (mNotificationListRequestReceiver != null) {
                mBroadcastManager.unregisterReceiver(mNotificationListRequestReceiver);
                mNotificationListRequestReceiver = null;
            }

            if (mNotificationDismissReceiver != null) {
                mBroadcastManager.unregisterReceiver(mNotificationDismissReceiver);
                mNotificationDismissReceiver = null;
            }

            mBroadcastManager = null;
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (mBroadcastManager != null) {
            JSONObject notification = parseNotification(sbn);

            if (notification != null) {
                Intent result = new Intent(Constants.INTENT.NOTIFICATION_RECEIVED);

                result.putExtra("notification", notification.toString());

                mBroadcastManager.sendBroadcast(result);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (mBroadcastManager != null) {
            JSONObject notification = parseNotification(sbn, true);

            if (notification != null) {
                Intent result = new Intent(Constants.INTENT.NOTIFICATION_REMOVED);

                result.putExtra("notification", notification.toString());

                mBroadcastManager.sendBroadcast(result);
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals("notifications_excluded_applications")) {
            mExcludedApplications = sharedPreferences.getStringSet("notifications_excluded_applications", new HashSet<>());
        }
    }

    private JSONObject parseNotification(StatusBarNotification notification) {
        return parseNotification(notification, false);
    }

    private JSONObject parseNotification(StatusBarNotification notification, boolean ignoreFilters) {
        String packageName = notification.getPackageName();

        if (packageName.equals(getPackageName())) {
            return null;
        }

        if ((notification.getNotification().flags & Notification.FLAG_LOCAL_ONLY) != 0
                || (notification.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null;
        }

        if (!ignoreFilters && mExcludedApplications.contains(packageName)) {
            return null;
        }

        JSONObject result = new JSONObject();

        try {
            result.put("key", notification.getKey());

            String applicationName = getApplicationName(packageName);

            if (applicationName == null) {
                return null;
            }

            applicationName = applicationName.trim();

            if (applicationName.length() < 1) {
                return null;
            }

            result.put("app_name", applicationName);

            Bundle extras = notification.getNotification().extras;

            String title = "";

            CharSequence extraBigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG);

            if (extraBigTitle != null && TextUtils.getTrimmedLength(extraBigTitle) > 0) {
                title = extraBigTitle.toString().trim();
            } else {
                CharSequence extraTitle = extras.getCharSequence(Notification.EXTRA_TITLE);

                if (extraTitle != null && TextUtils.getTrimmedLength(extraTitle) > 0) {
                    title = extraTitle.toString().trim();
                }
            }

            title = title.replaceAll(" +", " ");
            title = ellipsize(title, Constants.NOTIFICATION.MAX_TITLE_LENGTH, true);

            if (title.length() < 1) {
                return null;
            }

            result.put("title", title);

            String text = "";

            CharSequence extraBigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

            if (extraBigText != null && TextUtils.getTrimmedLength(extraBigText) > 0) {
                text = extraBigText.toString().trim();
            } else {
                CharSequence extraText = extras.getCharSequence(Notification.EXTRA_TEXT);

                if (extraText != null && TextUtils.getTrimmedLength(extraText) > 0) {
                    text = extraText.toString().trim();
                }
            }

            text = text.replaceAll(" +", " ");
            text = ellipsize(text, Constants.NOTIFICATION.MAX_TEXT_LENGTH, false);

            if (text.length() > 0) {
                result.put("text", text);
            }

            if (!notification.isClearable()) {
                result.put("persistent", true);
            }
        } catch (JSONException e) {
            return null;
        }

        return result;
    }

    private String getApplicationName(String packageName) {
        PackageManager packageManager = getPackageManager();

        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);

            if (applicationInfo == null) {
                return null;
            }

            CharSequence applicationName = packageManager.getApplicationLabel(applicationInfo);

            if (TextUtils.isEmpty(applicationName) || applicationName.toString().startsWith(packageName)) {
                return null;
            }

            return applicationName.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void restartNotificationListener() {
        PackageManager packageManager = getPackageManager();

        ComponentName componentName = new ComponentName(getApplicationContext(),
                NotificationService.class);

        packageManager.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        packageManager.setComponentEnabledSetting(componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(componentName);
        }
    }

    private String ellipsize(String input, int maxLen, boolean singleLine) {
        if (maxLen < ELLIPSIS.length()) {
            return "";
        }

        Scanner scanner = new Scanner(input);

        if (!scanner.hasNextLine()) {
            scanner.close();

            return "";
        }

        String firstLine = scanner.nextLine();

        if (singleLine
                || !scanner.hasNextLine()
                || firstLine.length() + mLineSeparator.length() + ELLIPSIS.length() > maxLen) {
            if (firstLine.length() > maxLen) {
                firstLine = firstLine.substring(0, maxLen - ELLIPSIS.length()) + ELLIPSIS;
            }

            if (scanner.hasNextLine() && !firstLine.endsWith(ELLIPSIS)) {
                scanner.close();

                if (firstLine.length() + ELLIPSIS.length() <= maxLen) {
                    return (firstLine + ELLIPSIS);
                }

                return (firstLine.substring(0, maxLen - ELLIPSIS.length()) + ELLIPSIS);
            }

            scanner.close();

            return firstLine;
        }

        if (input.length() <= maxLen) {
            scanner.close();

            return input;
        }

        StringBuilder lines = new StringBuilder(firstLine);

        while (scanner.hasNextLine()) {
            String newLine = mLineSeparator + scanner.nextLine();

            if ((lines.length()
                    + newLine.length()
                    + mLineSeparator.length()
                    + ELLIPSIS.length()) > maxLen) {
                break;
            }

            lines.append(newLine);
        }

        scanner.close();

        lines.append(mLineSeparator).append(ELLIPSIS);

        return lines.toString();
    }

    private class NotificationListRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mConnected) {
                Intent result = new Intent(Constants.INTENT.NOTIFICATION_LIST);

                JSONArray array = new JSONArray();

                StatusBarNotification[] notifications = getActiveNotifications();

                if (notifications != null) {
                    for (StatusBarNotification notification : notifications) {
                        JSONObject object = parseNotification(notification);

                        if (object != null) {
                            array.put(object);
                        }
                    }
                }

                result.putExtra("notifications", array.toString());

                mBroadcastManager.sendBroadcast(result);
            }
        }
    }

    private class NotificationDismissReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String key = intent.getStringExtra("key");

            if (key != null && mConnected) {
                cancelNotification(key);
            }
        }
    }
}
