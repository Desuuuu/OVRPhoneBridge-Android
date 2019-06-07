package com.desuuuu.ovrphonebridge.settings;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import com.desuuuu.ovrphonebridge.BuildConfig;
import com.desuuuu.ovrphonebridge.Constants;
import com.desuuuu.ovrphonebridge.MainActivity;
import com.desuuuu.ovrphonebridge.R;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

public class MainFragment extends PreferenceFragmentCompat {
    private SharedPreferences mSharedPreferences;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Objects.requireNonNull(getContext());

        getPreferenceManager().setSharedPreferencesName(Constants.PREFERENCES_NAME);
        getPreferenceManager().setSharedPreferencesMode(Context.MODE_PRIVATE);

        mSharedPreferences = getPreferenceManager().getSharedPreferences();

        setPreferencesFromResource(R.xml.preferences_main, rootKey);

        EditTextPreference serverAddress = Objects.requireNonNull(findPreference("server_address"));

        serverAddress.setOnBindEditTextListener(EditText::selectAll);

        serverAddress.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
            String text = preference.getText();

            if (TextUtils.isEmpty(text)) {
                return getString(R.string.summary_not_set);
            }

            return text;
        });

        serverAddress.setOnPreferenceChangeListener((preference, newValue) -> {
            String strVal = newValue.toString();

            if (TextUtils.isEmpty(strVal)) {
                return true;
            }

            if (InternetDomainName.isValid(strVal) || InetAddresses.isInetAddress(strVal)) {
                return true;
            }

            Toast.makeText(getContext(), R.string.invalid_server_address, Toast.LENGTH_LONG).show();
            return false;
        });

        EditTextPreference serverPort = Objects.requireNonNull(findPreference("server_port"));

        serverPort.setOnBindEditTextListener(editText -> {
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);

            if (TextUtils.isEmpty(editText.getText().toString())) {
                editText.setText(Constants.DEFAULT.PORT);
            }

            editText.selectAll();
        });

        serverPort.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
            String text = preference.getText();

            if (TextUtils.isEmpty(text)) {
                return Constants.DEFAULT.PORT;
            }

            return text;
        });

        serverPort.setOnPreferenceChangeListener((preference, newValue) -> {
            String strVal = newValue.toString();

            if (TextUtils.isEmpty(strVal)) {
                Toast.makeText(getContext(), R.string.invalid_server_port, Toast.LENGTH_LONG).show();
                return false;
            }

            int intVal;

            try {
                intVal = Integer.parseInt(strVal);
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), R.string.invalid_server_port, Toast.LENGTH_LONG).show();
                return false;
            }

            if (intVal < 1 || intVal > 65535) {
                Toast.makeText(getContext(), R.string.invalid_server_port, Toast.LENGTH_LONG).show();
                return false;
            }

            return true;
        });

        SwitchPreference retryForever = Objects.requireNonNull(findPreference("retry_forever"));

        retryForever.setDefaultValue(Constants.DEFAULT.RETRY_FOREVER);

        Preference notificationsFilters = Objects.requireNonNull(findPreference("notifications_filters"));

        SwitchPreference featureNotifications = Objects.requireNonNull(findPreference("feature_notifications"));

        featureNotifications.setDefaultValue(Constants.DEFAULT.FEATURE_NOTIFICATIONS);

        featureNotifications.setOnPreferenceChangeListener((preference, newValue) -> {
            notificationsFilters.setEnabled((boolean)newValue);

            if ((boolean)newValue && !MainActivity.isNotificationListenerEnabled(getContext())) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }

            return true;
        });

        notificationsFilters.setEnabled(featureNotifications.isChecked());

        TelephonyManager telephonyManager = (TelephonyManager)getContext().getSystemService(
                Context.TELEPHONY_SERVICE);

        SwitchPreference featureSMS = Objects.requireNonNull(findPreference("feature_sms"));

        if (telephonyManager.isSmsCapable()) {
            featureSMS.setDefaultValue(Constants.DEFAULT.FEATURE_SMS);

            featureSMS.setOnPreferenceChangeListener((preference, newValue) -> {
                if ((boolean)newValue && !MainActivity.hasSMSPermissions(getContext())) {
                    requestPermissions(new String[] {
                            Manifest.permission.READ_SMS,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.READ_PHONE_STATE
                    }, Constants.PERMISSION.SMS_REQUEST);
                }

                return true;
            });

            featureSMS.setVisible(true);
        } else {
            featureSMS.setVisible(false);
        }

        EditTextPreference deviceName = Objects.requireNonNull(findPreference("device_name"));

        deviceName.setOnBindEditTextListener(editText -> {
            if (TextUtils.isEmpty(editText.getText().toString())) {
                editText.setText(MainActivity.getDeviceName());
            }

            editText.selectAll();
        });

        deviceName.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) preference -> {
            String text = preference.getText();

            if (TextUtils.isEmpty(text)) {
                String defaultName = MainActivity.getDeviceName();

                if (TextUtils.isEmpty(defaultName)) {
                    return getString(R.string.summary_not_set);
                }

                return defaultName;
            }

            return text;
        });

        deviceName.setOnPreferenceChangeListener((preference, newValue) -> {
            String strVal = newValue.toString();

            if (TextUtils.isEmpty(strVal)) {
                Toast.makeText(getContext(), R.string.invalid_device_name, Toast.LENGTH_LONG).show();
                return false;
            }

            return true;
        });

        Preference identifier = Objects.requireNonNull(findPreference("identifier"));

        identifier.setSummary(mSharedPreferences.getString("identifier", getString(R.string.summary_not_set)));

        Preference clearAllowedServers = Objects.requireNonNull(findPreference("clear_allowed_servers"));

        Set<String> mAllowedServers = Objects.requireNonNull(mSharedPreferences.getStringSet(
                "allowed_servers",
                new HashSet<>()));

        clearAllowedServers.setEnabled(mAllowedServers.size() > 0);

        clearAllowedServers.setOnPreferenceClickListener(preference -> {
            mSharedPreferences.edit().remove("allowed_servers").commit();

            clearAllowedServers.setEnabled(false);

            return true;
        });

        Preference version = Objects.requireNonNull(findPreference("version"));

        version.setSummary(BuildConfig.VERSION_NAME);

        Preference feedback = Objects.requireNonNull(findPreference("feedback"));

        Intent feedbackIntent = new Intent(Intent.ACTION_VIEW);
        feedbackIntent.setData(Uri.parse(Constants.FEEDBACK_URL));

        feedback.setIntent(feedbackIntent);
    }

    @Override
    public void onResume() {
        super.onResume();

        Objects.requireNonNull(getContext());

        if (!MainActivity.isNotificationListenerEnabled(getContext())) {
            SwitchPreference featureNotifications = findPreference("feature_notifications");

            if (featureNotifications != null) {
                featureNotifications.setChecked(false);
            }
        }

        if (!MainActivity.hasSMSPermissions(getContext())) {
            SwitchPreference featureSMS = findPreference("feature_sms");

            if (featureSMS != null) {
                featureSMS.setChecked(false);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == Constants.PERMISSION.SMS_REQUEST) {
            if (grantResults.length != 4
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED
                    || grantResults[1] != PackageManager.PERMISSION_GRANTED
                    || grantResults[2] != PackageManager.PERMISSION_GRANTED
                    || grantResults[3] != PackageManager.PERMISSION_GRANTED) {
                SwitchPreference featureSMS = findPreference("feature_sms");

                if (featureSMS != null) {
                    featureSMS.setChecked(false);
                }
            }
        }
    }
}
