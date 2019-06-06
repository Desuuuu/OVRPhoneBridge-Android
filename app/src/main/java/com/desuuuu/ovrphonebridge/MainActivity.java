package com.desuuuu.ovrphonebridge;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Objects;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {
    private SharedPreferences mSharedPreferences;
    private LocalBroadcastManager mBroadcastManager;
    private StatusNotificationReceiver mStatusNotificationReceiver;

    private Switch mServiceSwitch;
    private TextView mServiceText;

    private boolean mActivityVisible = false;
    private int mConnectionStatus = Constants.SERVICE.STATUS_STOPPED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mSharedPreferences = getSharedPreferences(Constants.PREFERENCES_NAME, Context.MODE_PRIVATE);

        mServiceSwitch = findViewById(R.id.serviceSwitch);
        mServiceText = findViewById(R.id.serviceText);

        mBroadcastManager = LocalBroadcastManager.getInstance(this);

        mStatusNotificationReceiver = new StatusNotificationReceiver();

        mBroadcastManager.registerReceiver(
                mStatusNotificationReceiver,
                new IntentFilter(Constants.INTENT.STATUS_NOTIFICATION));

        mBroadcastManager.sendBroadcastSync(new Intent(Constants.INTENT.STATUS_REQUEST));

        updateLayout();

        mServiceSwitch.setVisibility(View.VISIBLE);
        mServiceText.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mBroadcastManager != null && mStatusNotificationReceiver != null) {
            mBroadcastManager.unregisterReceiver(mStatusNotificationReceiver);

            if (mConnectionStatus == Constants.SERVICE.STATUS_DISCONNECTED) {
                Intent intent = new Intent(MainActivity.this, ConnectionService.class);

                stopService(intent);
            }

            mBroadcastManager = null;
            mStatusNotificationReceiver = null;
        }

        if (mServiceSwitch != null) {
            mServiceSwitch.setOnCheckedChangeListener(null);

            mServiceSwitch = null;
        }
    }

    private void updateLayout() {
        mServiceSwitch.setOnCheckedChangeListener(null);

        switch (mConnectionStatus) {
            case Constants.SERVICE.STATUS_CONNECTING:
                mServiceSwitch.setChecked(true);

                mServiceText.setText(R.string.connecting);
                break;

            case Constants.SERVICE.STATUS_CONNECTED:
                mServiceSwitch.setChecked(true);

                mServiceText.setText(R.string.connected);
                break;

            default:
                mServiceSwitch.setChecked(false);

                mServiceText.setText(R.string.disconnected);
                break;
        }

        mServiceSwitch.setOnCheckedChangeListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mActivityVisible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();

        mActivityVisible = false;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent settingsIntent = new Intent(this, com.desuuuu.ovrphonebridge.settings.MainActivity.class);
            startActivity(settingsIntent);

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (!mActivityVisible) {
            return;
        }

        if (isChecked) {
            try {
                checkSettings();
            } catch (Exception e) {
                mServiceSwitch.setChecked(false);

                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();

                Intent settingsIntent = new Intent(this, com.desuuuu.ovrphonebridge.settings.MainActivity.class);
                startActivity(settingsIntent);

                return;
            }

            Intent intent = new Intent(MainActivity.this, ConnectionService.class);

            intent.setAction(Constants.INTENT.START_CONNECTION_SERVICE);

            ContextCompat.startForegroundService(this, intent);
        } else if (mConnectionStatus != Constants.SERVICE.STATUS_STOPPED) {
            Intent intent = new Intent(MainActivity.this, ConnectionService.class);

            stopService(intent);
        }
    }

    private void checkSettings() throws Exception {
        String serverAddress = mSharedPreferences.getString("server_address", null);

        if (TextUtils.isEmpty(serverAddress)) {
            throw new Exception(getString(R.string.missing_server_address));
        }

        String serverPort = mSharedPreferences.getString("server_port", Constants.DEFAULT.PORT);

        if (TextUtils.isEmpty(serverPort)) {
            throw new Exception(getString(R.string.missing_server_port));
        }

        int port;

        try {
            port = Integer.parseInt(Objects.requireNonNull(serverPort));
        } catch (NumberFormatException e) {
            throw new Exception(getString(R.string.invalid_server_port));
        }

        if (port < 1 || port > 65535) {
            throw new Exception(getString(R.string.invalid_server_port));
        }

        String deviceName = mSharedPreferences.getString("device_name", getDeviceName());

        if (TextUtils.isEmpty(deviceName)) {
            throw new Exception(getString(R.string.missing_device_name));
        }
    }

    private class StatusNotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            mConnectionStatus = intent.getIntExtra("status", Constants.SERVICE.STATUS_STOPPED);

            String message = intent.getStringExtra("message");

            if (mActivityVisible && !TextUtils.isEmpty(message)) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }

            if (mServiceSwitch != null && mServiceText != null) {
                updateLayout();
            }
        }
    }

    public static String getDeviceName() {
        String result;

        if (TextUtils.isEmpty(Build.MODEL)) {
            if (TextUtils.isEmpty(Build.MANUFACTURER)) {
                return Constants.DEFAULT.DEVICE_NAME;
            }

            result = Build.MANUFACTURER;
        } else {
            if (TextUtils.isEmpty(Build.MANUFACTURER)
                    || Build.MODEL.toLowerCase().startsWith(Build.MANUFACTURER.toLowerCase())) {
                result = Build.MODEL;
            } else {
                result = (Build.MANUFACTURER + " " + Build.MODEL);
            }
        }

        if (result.length() > 32) {
            result = result.substring(0, 32);
        }

        return result;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService(
                Context.CONNECTIVITY_SERVICE);

        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

        return (networkInfo != null && networkInfo.isConnected());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isNotificationListenerEnabled(Context context) {
        Set<String> enabledListenerPackages = NotificationManagerCompat.getEnabledListenerPackages(
                context);

        return enabledListenerPackages.contains(context.getPackageName());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean hasSMSPermissions(Context context) {
        TelephonyManager telephonyManager = (TelephonyManager)context.getSystemService(
                Context.TELEPHONY_SERVICE);

        return (telephonyManager.isSmsCapable()
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED);
    }
}
