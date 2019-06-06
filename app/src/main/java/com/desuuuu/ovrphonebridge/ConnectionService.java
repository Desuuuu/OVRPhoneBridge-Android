package com.desuuuu.ovrphonebridge;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContentResolverCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import io.michaelrocks.libphonenumber.android.NumberParseException;
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil;
import io.michaelrocks.libphonenumber.android.Phonenumber.PhoneNumber;

public class ConnectionService extends Service {
    private static final String TAG = "ConnectionService";

    private Handler mMainHandler;
    private SimpleDateFormat mDateFormatter;
    private PhoneNumberUtil mPhoneNumberUtil;
    private PowerManager.WakeLock mWakeLock;
    private LocalBroadcastManager mBroadcastManager;

    private StatusRequestReceiver mStatusRequestReceiver;
    private NotificationListReceiver mNotificationListReceiver;
    private NotificationReceivedReceiver mNotificationReceivedReceiver;
    private NotificationRemovedReceiver mNotificationRemovedReceiver;
    private SmsSentReceiver mSmsSentReceiver;

    private Runnable mConnect;
    private Runnable mHandshakeTimeout;
    private Runnable mCheckNotificationService;
    private ConnectionHandler mConnectionHandler;

    private int mStatus = Constants.SERVICE.STATUS_STOPPED;
    private boolean mHandshakeDone;
    private int mRetryAttempt;

    private Crypto mCrypto;
    private String mServerAddress;
    private int mServerPort;
    private boolean mRetryForever;
    private boolean mFeatureNotifications;
    private boolean mFeatureSMS;
    private String mDeviceName;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            startService();
        } else {
            String action = intent.getAction();

            if (action != null) {
                switch (action) {
                    case Constants.INTENT.START_CONNECTION_SERVICE:
                        startService();
                        break;

                    case Constants.INTENT.STOP_CONNECTION_SERVICE:
                        stopService();
                        break;

                    case Constants.INTENT.DISCONNECT_CONNECTION_SERVICE:
                        disconnect();
                        break;
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mStatus != Constants.SERVICE.STATUS_STOPPED) {
            stopService();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("SimpleDateFormat")
    private void startService() {
        if (mStatus == Constants.SERVICE.STATUS_DISCONNECTED) {
            startForeground(Constants.NOTIFICATION.ID_CONNECTION_SERVICE, buildNotification());

            mRetryAttempt = 0;

            connect();
            return;
        }

        if (mStatus != Constants.SERVICE.STATUS_STOPPED) {
            startForeground(Constants.NOTIFICATION.ID_CONNECTION_SERVICE, buildNotification());

            return;
        }

        Log.d(TAG, "Starting service");

        mBroadcastManager = LocalBroadcastManager.getInstance(this);

        mStatus = Constants.SERVICE.STATUS_CONNECTING;

        broadcastStatus();

        startForeground(Constants.NOTIFICATION.ID_CONNECTION_SERVICE, buildNotification());

        mMainHandler = new Handler();
        mDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        mPhoneNumberUtil = PhoneNumberUtil.createInstance(this);

        PowerManager powerManager = (PowerManager)getSystemService(Activity.POWER_SERVICE);

        mWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                Constants.WAKELOCK.TAG);

        mWakeLock.acquire();

        mStatusRequestReceiver = new StatusRequestReceiver();
        mNotificationListReceiver = new NotificationListReceiver();
        mNotificationReceivedReceiver = new NotificationReceivedReceiver();
        mNotificationRemovedReceiver = new NotificationRemovedReceiver();

        mSmsSentReceiver = new SmsSentReceiver();

        mBroadcastManager.registerReceiver(mStatusRequestReceiver,
                new IntentFilter(Constants.INTENT.STATUS_REQUEST));

        mBroadcastManager.registerReceiver(mNotificationListReceiver,
                new IntentFilter(Constants.INTENT.NOTIFICATION_LIST));

        mBroadcastManager.registerReceiver(mNotificationReceivedReceiver,
                new IntentFilter(Constants.INTENT.NOTIFICATION_RECEIVED));

        mBroadcastManager.registerReceiver(mNotificationRemovedReceiver,
                new IntentFilter(Constants.INTENT.NOTIFICATION_REMOVED));

        registerReceiver(mSmsSentReceiver, new IntentFilter(Constants.INTENT.SMS_SENT));

        mConnect = this::connect;
        mHandshakeTimeout = () -> onSocketHandshakeFail(getString(R.string.handshake_timeout));
        mCheckNotificationService = this::checkNotificationService;

        mRetryAttempt = 0;

        connect();
    }

    private void stopService() {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            stopSelf();
            return;
        }

        Log.d(TAG, "Stopping service");

        disconnect(true);

        mStatus = Constants.SERVICE.STATUS_STOPPED;

        broadcastStatus();

        mBroadcastManager.unregisterReceiver(mStatusRequestReceiver);
        mBroadcastManager.unregisterReceiver(mNotificationListReceiver);
        mBroadcastManager.unregisterReceiver(mNotificationReceivedReceiver);
        mBroadcastManager.unregisterReceiver(mNotificationRemovedReceiver);

        unregisterReceiver(mSmsSentReceiver);

        stopForeground(true);

        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }

        stopSelf();
    }

    private void connect() {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        try {
            parseSettings();
        } catch (Exception e) {
            disconnect(e.getMessage());
            return;
        }

        if (!MainActivity.isNetworkAvailable(this)) {
            disconnect(getString(R.string.network_unavailable));
            return;
        }

        Log.d(TAG, "Connecting to " + mServerAddress + ":" + mServerPort);

        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }

        mMainHandler.removeCallbacksAndMessages(null);

        mHandshakeDone = false;

        if (mStatus != Constants.SERVICE.STATUS_CONNECTING) {
            mStatus = Constants.SERVICE.STATUS_CONNECTING;

            broadcastStatus();

            updateForegroundNotification(buildNotification());
        }

        if (mFeatureNotifications) {
            checkNotificationService();
        }

        HandlerThread connectionThread = new HandlerThread("ConnectionWriterThread");
        connectionThread.start();

        if (mConnectionHandler != null) {
            mConnectionHandler.stop();
            mConnectionHandler.sendEmptyMessage(Constants.MESSAGE.DISCONNECT);

            mConnectionHandler.getLooper().quitSafely();

            mMainHandler.removeCallbacksAndMessages(null);
        }

        mConnectionHandler = new ConnectionHandler(mServerAddress, mServerPort, connectionThread.getLooper());

        if (!mConnectionHandler.sendEmptyMessage(Constants.MESSAGE.CONNECT)) {
            Log.e(TAG, "Failed to start ConnectionWriterThread");

            disconnect(getString(R.string.connection_failed));

            mMainHandler.postDelayed(() -> {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            }, 3000);
        }
    }

    private void disconnect() {
        disconnect(false, null);
    }

    private void disconnect(boolean silent) {
        disconnect(silent, null);
    }

    private void disconnect(String message) {
        disconnect(false, message);
    }

    private void disconnect(boolean silent, String message) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED
                || mStatus == Constants.SERVICE.STATUS_DISCONNECTED) {
            return;
        }

        Log.d(TAG, "Disconnecting");

        if (mConnectionHandler != null) {
            mConnectionHandler.stop();
            mConnectionHandler.sendEmptyMessage(Constants.MESSAGE.DISCONNECT);

            mConnectionHandler.getLooper().quitSafely();

            mConnectionHandler = null;
        }

        mMainHandler.removeCallbacksAndMessages(null);

        mStatus = Constants.SERVICE.STATUS_DISCONNECTED;

        mHandshakeDone = false;

        if (!silent) {
            broadcastStatus(message);

            updateForegroundNotification(buildNotification(message));
        }
    }

    private boolean sendSocketMessage(JSONObject message) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED
                || mStatus == Constants.SERVICE.STATUS_DISCONNECTED
                || mConnectionHandler == null) {
            return false;
        }

        try {
            String encrypted = mCrypto.encrypt(message.toString());

            Message msg = Message.obtain(
                    mConnectionHandler,
                    Constants.MESSAGE.MESSAGE,
                    encrypted);

            return mConnectionHandler.sendMessage(msg);
        } catch (Exception e) {
            Log.d(TAG, "Encryption failed");

            e.printStackTrace();
        }

        return false;
    }

    private void onSocketConnect() {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.d(TAG, "Socket connected");

        JSONObject message;

        try {
            JSONObject features = new JSONObject();

            features.put("notifications", mFeatureNotifications);
            features.put("sms", mFeatureSMS);

            message = new JSONObject();

            message.put("type", "handshake");
            message.put("features", features);
            message.put("app_version", BuildConfig.VERSION_NAME);
            message.put("device_name", mDeviceName);
            message.put("os_type", "android");
            message.put("os_version", Build.VERSION.RELEASE);
        } catch (JSONException e) {
            e.printStackTrace();

            onSocketHandshakeFail(getString(R.string.handshake_failed));

            return;
        }

        mMainHandler.postDelayed(mHandshakeTimeout, Constants.HANDSHAKE_TIMEOUT);

        if (!sendSocketMessage(message)) {
            onSocketHandshakeFail(getString(R.string.handshake_failed));
        }
    }

    private void onSocketHandshakeSuccess() {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.d(TAG, "Handshake successful");

        mMainHandler.removeCallbacks(mHandshakeTimeout);

        mRetryAttempt = 0;

        mHandshakeDone = true;

        mStatus = Constants.SERVICE.STATUS_CONNECTED;

        broadcastStatus();

        updateForegroundNotification(buildNotification());
    }

    private void onSocketHandshakeFail(String message) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.e(TAG, "Handshake failed");

        mRetryAttempt = 0;

        disconnect(message);

        mMainHandler.postDelayed(() -> {
            if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }, 3000);
    }

    private void onSocketConnectFail(Exception e) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.e(TAG, "Socket connection failed");

        e.printStackTrace();

        if (mRetryAttempt >= Constants.MAX_RETRY) {
            disconnect(getString(R.string.connection_failed));

            mMainHandler.postDelayed(() -> {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                }
            }, 3000);
            return;
        }

        disconnect(true);

        mStatus = Constants.SERVICE.STATUS_CONNECTING;

        broadcastStatus(getString(R.string.connection_failed_retry));

        updateForegroundNotification(buildNotification());

        if (!mRetryForever) {
            mRetryAttempt++;

            if (mRetryAttempt == Integer.MAX_VALUE) {
                mRetryAttempt = 0;
            }
        }

        mMainHandler.postDelayed(mConnect, 5000);
    }

    private void onSocketDisconnect(Exception e) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.d(TAG, "Socket disconnected");

        if (e != null) {
            e.printStackTrace();
        }

        disconnect(true);

        mStatus = Constants.SERVICE.STATUS_CONNECTING;

        broadcastStatus(getString(R.string.connection_lost_retry));

        updateForegroundNotification(buildNotification());

        mMainHandler.postDelayed(mConnect, 10000);
    }

    private void onSocketMessage(String data) {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        Log.d(TAG, "Message received");

        String decrypted;

        try {
            decrypted = mCrypto.decrypt(data);
        } catch (Exception e) {
            if (mHandshakeDone) {
                Log.e(TAG, "Decryption failed");

                e.printStackTrace();
            } else {
                onSocketHandshakeFail(getString(R.string.bad_encryption_password));
            }
            return;
        }

        try {
            JSONObject message = new JSONObject(decrypted);

            String type = message.getString("type");

            if (type == null) {
                throw new JSONException("Missing type property");
            }

            if (!mHandshakeDone) {
                if (type.equals("handshake")) {
                    if (message.getBoolean("success")) {
                        onSocketHandshakeSuccess();
                    } else {
                        onSocketHandshakeFail(getString(R.string.handshake_failed));
                    }
                    return;
                }

                Log.d(TAG, "Ignoring message, handshake pending");
                return;
            }

            switch (type) {
                case "list_notifications":
                    listNotifications();
                    break;

                case "dismiss_notification":
                    String key = message.getString("key");

                    if (key != null) {
                        dismissNotifications(key);
                    }
                    break;

                case "list_sms":
                    listSMS();
                    break;

                case "list_sms_from":
                    String number = message.getString("number");
                    int page = 0;

                    if (message.has("page")) {
                        page = message.getInt("page");
                    }

                    if (number != null && page >= 0) {
                        listSMSFromNumber(number, page);
                    }
                    break;

                case "send_sms":
                    String destination = message.getString("destination");
                    String body = message.getString("body");

                    if (destination != null && body != null) {
                        sendSMS(destination, body);
                    }
                    break;

                default:
                    Log.w(TAG, "Unknown message type: " + type);
                    break;
            }
        } catch (JSONException e) {
            Log.e(TAG, "Invalid message");

            e.printStackTrace();
        }
    }

    private void parseSettings() throws Exception {
        Log.d(TAG, "Parsing settings");

        SharedPreferences sharedPreferences = getSharedPreferences(
                Constants.PREFERENCES_NAME,
                Context.MODE_PRIVATE);

        String serverAddress = sharedPreferences.getString("server_address", null);

        if (TextUtils.isEmpty(serverAddress)) {
            throw new Exception(getString(R.string.missing_server_address));
        }

        String serverPort = sharedPreferences.getString("server_port", Constants.DEFAULT.PORT);

        if (TextUtils.isEmpty(serverPort)) {
            throw new Exception(getString(R.string.missing_server_port));
        }

        String deviceName = sharedPreferences.getString("device_name", MainActivity.getDeviceName());

        if (TextUtils.isEmpty(deviceName)) {
            throw new Exception(getString(R.string.missing_device_name));
        }

        try {
            mServerPort = Integer.parseInt(Objects.requireNonNull(serverPort));
        } catch (NumberFormatException e) {
            throw new Exception(getString(R.string.invalid_server_port));
        }

        if (mServerPort < 1 || mServerPort > 65535) {
            throw new Exception(getString(R.string.invalid_server_port));
        }

        mServerAddress = serverAddress;

        try {
            mCrypto = new Crypto(encryptionPassword);
        } catch (Exception e) {
            throw new Exception(getString(R.string.encryption_failed));
        }

        mRetryForever = sharedPreferences.getBoolean(
                "retry_forever",
                Constants.DEFAULT.RETRY_FOREVER);

        mFeatureNotifications = sharedPreferences.getBoolean(
                "feature_notifications",
                Constants.DEFAULT.FEATURE_NOTIFICATIONS);

        if (mFeatureNotifications && !MainActivity.isNotificationListenerEnabled(this)) {
            mFeatureNotifications = false;
        }

        mFeatureSMS = sharedPreferences.getBoolean("feature_sms", Constants.DEFAULT.FEATURE_SMS);

        if (mFeatureSMS && !MainActivity.hasSMSPermissions(this)) {
            mFeatureSMS = false;
        }

        mDeviceName = deviceName;
    }

    private void checkNotificationService() {
        if (mStatus == Constants.SERVICE.STATUS_STOPPED) {
            return;
        }

        mMainHandler.removeCallbacks(mCheckNotificationService);

        if (!mFeatureNotifications || !MainActivity.isNotificationListenerEnabled(this)) {
            return;
        }

        if (mStatus == Constants.SERVICE.STATUS_CONNECTING || mStatus == Constants.SERVICE.STATUS_CONNECTED) {
            Intent intent = new Intent(this, NotificationService.class);

            intent.setAction(Constants.INTENT.START_NOTIFICATION_SERVICE);

            startService(intent);

            mMainHandler.postDelayed(mCheckNotificationService, Constants.NOTIFICATION.SERVICE_CHECK_INTERVAL);
        }
    }

    private void listNotifications() {
        if (!mFeatureNotifications || !MainActivity.isNotificationListenerEnabled(this)) {
            return;
        }

        if (mBroadcastManager != null) {
            mBroadcastManager.sendBroadcast(new Intent(Constants.INTENT.NOTIFICATION_LIST_REQUEST));
        }
    }

    private void dismissNotifications(String key) {
        if (!mFeatureNotifications || !MainActivity.isNotificationListenerEnabled(this)) {
            return;
        }

        Intent request = new Intent(Constants.INTENT.NOTIFICATION_DISMISS);

        request.putExtra("key", key);

        if (mBroadcastManager != null) {
            mBroadcastManager.sendBroadcast(request);
        }
    }

    private void listSMS() {
        if (!mFeatureSMS || !MainActivity.hasSMSPermissions(this)) {
            return;
        }

        String sort = (Telephony.Sms.DATE + " DESC LIMIT 200");

        Cursor cursor = ContentResolverCompat.query(
                getContentResolver(),
                Telephony.Sms.CONTENT_URI,
                new String[] {
                    Telephony.Sms.TYPE,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                },
                null,
                null,
                sort,
                null);

        JSONArray array = new JSONArray();

        if (cursor != null) {
            HashSet<String> seenNumbers = new HashSet<>();

            while (cursor.moveToNext()) {
                SMS result = parseSMS(cursor);

                if (result == null) {
                    continue;
                }

                if (seenNumbers.contains(result.number)) {
                    continue;
                }

                seenNumbers.add(result.number);

                JSONObject sms = new JSONObject();

                try {
                    sms.put("type", result.type)
                            .put("name", result.name)
                            .put("number", result.number)
                            .put("body", result.body)
                            .put("date", result.date);
                } catch (JSONException e) {
                    e.printStackTrace();

                    continue;
                }

                array.put(sms);

                if (array.length() >= Constants.SMS_LIST_MAX) {
                    break;
                }
            }

            cursor.close();
        }

        try {
            JSONObject message = new JSONObject();

            message.put("type", "sms_list");
            message.put("list", array);

            sendSocketMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build message");

            e.printStackTrace();
        }
    }

    private void listSMSFromNumber(String number, int page) {
        if (!mFeatureSMS || !MainActivity.hasSMSPermissions(this)) {
            return;
        }

        TelephonyManager telephonyManager = (TelephonyManager)getSystemService(
                Context.TELEPHONY_SERVICE);

        String country = telephonyManager.getSimCountryIso();

        if (TextUtils.isEmpty(country)) {
            Log.e(TAG, "Failed to retrieve SIM country");

            return;
        }

        String[] variations = getPossibleNumberVariations(number, country.toUpperCase());

        StringBuilder selection = new StringBuilder();

        for (int i = 0; i < variations.length; i++) {
            if (i > 0) {
                selection.append(" OR ");
            }

            selection.append(Telephony.Sms.ADDRESS + " = ?");
        }

        String sort = (Telephony.Sms.DATE
                + " DESC LIMIT "
                + Constants.SMS_PER_PAGE
                + " OFFSET "
                + (page * Constants.SMS_PER_PAGE));

        Cursor cursor = ContentResolverCompat.query(
                getContentResolver(),
                Telephony.Sms.CONTENT_URI,
                new String[] {
                    Telephony.Sms.TYPE,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                },
                selection.toString(),
                variations,
                sort,
                null);

        ContactInfo contact = getContactInfo(number);

        JSONArray array = new JSONArray();

        if (cursor != null) {
            while (cursor.moveToNext()) {
                SMS result = parseSMS(cursor, contact);

                if (result == null) {
                    continue;
                }

                if (contact == null) {
                    contact = new ContactInfo();

                    contact.name = result.name;
                    contact.number = result.number;
                }

                JSONObject sms = new JSONObject();

                try {
                    sms.put("type", result.type)
                            .put("body", result.body)
                            .put("date", result.date);
                } catch (JSONException e) {
                    e.printStackTrace();

                    continue;
                }

                array.put(sms);
            }

            cursor.close();
        }

        try {
            JSONObject message = new JSONObject();

            message.put("type", "sms_from_list");

            if (contact == null) {
                message.put("name", null)
                        .put("number", number);
            } else {
                message.put("name", contact.name)
                        .put("number", contact.number);
            }

            message.put("page", page);
            message.put("list", array);

            sendSocketMessage(message);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build message");

            e.printStackTrace();
        }
    }

    private SMS parseSMS(Cursor cursor) {
        return parseSMS(cursor, null);
    }

    private SMS parseSMS(Cursor cursor, ContactInfo contact) {
        SMS message = new SMS();

        try {
            switch (cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))) {
                case Telephony.Sms.MESSAGE_TYPE_INBOX:
                    message.type = "in";
                    break;

                case Telephony.Sms.MESSAGE_TYPE_SENT:
                case Telephony.Sms.MESSAGE_TYPE_OUTBOX:
                    message.type = "out";
                    break;

                default:
                    return null;
            }

            message.number = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));

            if (contact == null) {
                contact = getContactInfo(message.number);

                if (contact == null) {
                    message.name = null;
                } else  {
                    message.name = contact.name;
                    message.number = contact.number;
                }
            } else {
                message.name = contact.name;
                message.number = contact.number;
            }

            message.body = cursor.getString(
                    cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)).trim();

            message.date = mDateFormatter.format(new Date(cursor.getLong(
                    cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))));
        } catch (Exception e) {
            e.printStackTrace();

            return null;
        }

        return message;
    }

    private void sendSMS(String destination, String body) {
        if (!mFeatureSMS || !MainActivity.hasSMSPermissions(this)) {
            return;
        }

        if (TextUtils.isEmpty(body) || body.length() > 160) {
            return;
        }

        SmsManager smsManager = SmsManager.getDefault();

        Intent smsSentIntent = new Intent(Constants.INTENT.SMS_SENT);
        smsSentIntent.putExtra("number", destination);
        smsSentIntent.putExtra("body", body);

        smsManager.sendTextMessage(
                destination,
                null,
                body,
                PendingIntent.getBroadcast(this, 0, smsSentIntent, PendingIntent.FLAG_UPDATE_CURRENT),
                null);
    }

    private void broadcastStatus() {
        broadcastStatus(false, null);
    }

    private void broadcastStatus(boolean sync) {
        broadcastStatus(sync, null);
    }

    private void broadcastStatus(String message) {
        broadcastStatus(false, message);
    }

    private void broadcastStatus(boolean sync, String message) {
        if (mBroadcastManager == null) {
            return;
        }

        Log.d(TAG, "Broadcasting status: " + mStatus);

        Intent statusIntent = new Intent(Constants.INTENT.STATUS_NOTIFICATION);
        statusIntent.putExtra("status", mStatus);

        if (message != null) {
            statusIntent.putExtra("message", message);
        }

        if (sync) {
            mBroadcastManager.sendBroadcastSync(statusIntent);
        } else {
            mBroadcastManager.sendBroadcast(statusIntent);
        }
    }

    private Notification buildNotification() {
        return buildNotification(null);
    }

    private Notification buildNotification(String message) {
        Log.d(TAG, "Building notification");

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        NotificationCompat.Builder builder = getNotificationBuilder();

        switch (mStatus) {
            case Constants.SERVICE.STATUS_CONNECTING:
                builder.setContentTitle(getString(R.string.connecting));
                builder.setContentText(message);
                break;

            case Constants.SERVICE.STATUS_CONNECTED:
                builder.setContentTitle(getString(R.string.connected));
                builder.setContentText(mServerAddress + ":" + mServerPort);
                break;

            default:
                builder.setContentTitle(getString(R.string.disconnected));
                builder.setContentText(message);
                break;
        }

        builder.setContentIntent(PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT));

        builder.setSmallIcon(R.drawable.ic_notification);
        builder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));
        builder.setOngoing(true);
        builder.setShowWhen(false);
        builder.setLocalOnly(true);
        builder.setOnlyAlertOnce(true);
        builder.setDefaults(0);
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);

        if (mStatus == Constants.SERVICE.STATUS_CONNECTING
                || mStatus == Constants.SERVICE.STATUS_CONNECTED) {
            Intent disconnectIntent = new Intent(this, ConnectionService.class);
            disconnectIntent.setAction(Constants.INTENT.DISCONNECT_CONNECTION_SERVICE);

            builder.addAction(
                    0,
                    getString(R.string.disconnect),
                    PendingIntent.getService(this, 0, disconnectIntent, 0));
        } else {
            Intent reconnectIntent = new Intent(this, ConnectionService.class);
            reconnectIntent.setAction(Constants.INTENT.START_CONNECTION_SERVICE);

            builder.addAction(
                    0,
                    getString(R.string.reconnect),
                    PendingIntent.getService(this, 0, reconnectIntent, 0));
        }

        Intent stopIntent = new Intent(this, ConnectionService.class);
        stopIntent.setAction(Constants.INTENT.STOP_CONNECTION_SERVICE);

        builder.addAction(
                0,
                getString(R.string.stop_service),
                PendingIntent.getService(this, 0, stopIntent, 0));

        return builder.build();
    }

    private void updateForegroundNotification(Notification notification) {
        Log.d(TAG, "Updating foreground notification");

        NotificationManager notificationManager = (NotificationManager)getSystemService(
                Activity.NOTIFICATION_SERVICE);

        notificationManager.notify(Constants.NOTIFICATION.ID_CONNECTION_SERVICE, notification);
    }

    private NotificationCompat.Builder getNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager)getSystemService(
                    Activity.NOTIFICATION_SERVICE);

            if (manager != null) {
                NotificationChannel channel = manager.getNotificationChannel(
                        Constants.NOTIFICATION.CHANNEL_CONNECTION_SERVICE);

                if (channel == null) {
                    channel = new NotificationChannel(
                            Constants.NOTIFICATION.CHANNEL_CONNECTION_SERVICE,
                            getString(R.string.connection_service_channel_name),
                            NotificationManager.IMPORTANCE_LOW);

                    channel.setDescription(getString(R.string.connection_service_channel_description));
                    channel.enableLights(false);
                    channel.enableVibration(false);

                    manager.createNotificationChannel(channel);
                }
            }
        }

        return new NotificationCompat.Builder(
                this,
                Constants.NOTIFICATION.CHANNEL_CONNECTION_SERVICE);
    }

    private ContactInfo getContactInfo(String number) {
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number));

        Cursor cursor = ContentResolverCompat.query(
                getContentResolver(),
                uri,
                new String[] {
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.NUMBER
                },
                null,
                null,
                null,
                null);

        ContactInfo contactInfo = null;

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                contactInfo = new ContactInfo();

                contactInfo.name = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME));

                contactInfo.number = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.NUMBER));
            }

            cursor.close();
        }

        return contactInfo;
    }

    private String[] getPossibleNumberVariations(String number, String country) {
        HashSet<String> results = new HashSet<>();

        results.add(number);

        try {
            PhoneNumber phoneNumber = mPhoneNumberUtil.parse(number, country);

            results.add(mPhoneNumberUtil.format(
                    phoneNumber,
                    PhoneNumberUtil.PhoneNumberFormat.NATIONAL));

            results.add(mPhoneNumberUtil.format(
                    phoneNumber,
                    PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL));

            results.add(mPhoneNumberUtil.format(
                    phoneNumber,
                    PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            e.printStackTrace();
        }

        HashSet<String> extras = new HashSet<>();

        Pattern pattern = Pattern.compile("\\s+");

        for (String entry : results) {
            Matcher matcher = pattern.matcher(entry);

            if (matcher.find()) {
                extras.add(matcher.replaceAll(""));
            }
        }

        results.addAll(extras);

        return results.toArray(new String[0]);
    }

    private class SMS {
        String type;
        String name;
        String number;
        String body;
        String date;
    }

    private class ContactInfo {
        String name;
        String number;
    }

    private class StatusRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            broadcastStatus(true);
        }
    }

    private class NotificationListReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStatus == Constants.SERVICE.STATUS_CONNECTED) {
                String notifications = intent.getStringExtra("notifications");

                if (notifications == null) {
                    return;
                }

                try {
                    JSONArray array = new JSONArray(notifications);

                    JSONObject message = new JSONObject();

                    message.put("type", "notification_list");
                    message.put("list", array);

                    sendSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to build message");

                    e.printStackTrace();
                }
            }
        }
    }

    private class NotificationReceivedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStatus == Constants.SERVICE.STATUS_CONNECTED) {
                String notification = intent.getStringExtra("notification");

                if (notification == null) {
                    return;
                }

                try {
                    JSONObject object = new JSONObject(notification);

                    JSONObject message = new JSONObject();

                    message.put("type", "notification_received");
                    message.put("notification", object);

                    sendSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to build message");

                    e.printStackTrace();
                }
            }
        }
    }

    private class NotificationRemovedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStatus == Constants.SERVICE.STATUS_CONNECTED) {
                String notification = intent.getStringExtra("notification");

                if (notification == null) {
                    return;
                }

                try {
                    JSONObject object = new JSONObject(notification);

                    JSONObject message = new JSONObject();

                    message.put("type", "notification_removed");
                    message.put("notification", object);

                    sendSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to build message");

                    e.printStackTrace();
                }
            }
        }
    }

    private class SmsSentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStatus == Constants.SERVICE.STATUS_CONNECTED) {
                String number = intent.getStringExtra("number");
                String body = intent.getStringExtra("body");

                if (number == null || body == null) {
                    return;
                }

                try {
                    JSONObject sms = new JSONObject();

                    sms.put("type", "out")
                            .put("body", body)
                            .put("date", mDateFormatter.format(new Date()));

                    JSONObject message = new JSONObject();

                    message.put("type", "sms_sent")
                            .put("number", number)
                            .put("success", (getResultCode() == Activity.RESULT_OK))
                            .put("sms", sms);

                    sendSocketMessage(message);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to build message");

                    e.printStackTrace();
                }
            }
        }
    }

    private class ConnectionHandler extends Handler {
        private static final String TAG = "ConnectionWriterThread";

        private String mServerAddress;
        private int mServerPort;

        private Socket mSocket;
        private BufferedWriter mBufferedWriter;

        private volatile boolean mConnected;
        private volatile boolean mStop;

        ConnectionHandler(String serverAddress, int serverPort, Looper looper) {
            super(looper);

            mServerAddress = serverAddress;
            mServerPort = serverPort;
            mStop = false;
            mConnected = false;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Constants.MESSAGE.CONNECT:
                    connect();
                    break;

                case Constants.MESSAGE.DISCONNECT:
                    disconnect();
                    break;

                case Constants.MESSAGE.MESSAGE:
                    String message = (String)msg.obj;

                    if (message != null) {
                        sendMessage(message);
                    }
                    break;

                case Constants.MESSAGE.READ_THREAD_EXIT:
                    if (mConnected) {
                        disconnect();

                        if (!mStop) {
                            Exception exception = (Exception)msg.obj;

                            mMainHandler.post(() -> onSocketDisconnect(exception));
                        }
                    }
                    break;
            }
        }

        void stop() {
            mStop = true;
        }

        private void connect() {
            if (mConnected || mStop) {
                return;
            }

            Log.d(TAG, "Connecting socket");

            mSocket = new Socket();

            try {
                mSocket.setKeepAlive(true);

                mSocket.connect(
                        new InetSocketAddress(mServerAddress, mServerPort),
                        Constants.SOCKET_TIMEOUT);

                if (mStop) {
                    return;
                }

                mConnected = true;

                mBufferedWriter = new BufferedWriter(new OutputStreamWriter(
                        mSocket.getOutputStream(),
                        StandardCharsets.UTF_8));

                Log.d(TAG, "Starting reader thread");

                ReaderThread readerThread = new ReaderThread("ConnectionReaderThread",
                        new BufferedReader(new InputStreamReader(mSocket.getInputStream(),
                                StandardCharsets.UTF_8)));

                readerThread.start();

                if (!mStop) {
                    mMainHandler.post(ConnectionService.this::onSocketConnect);
                }
            } catch (IOException e) {
                disconnect();

                if (!mStop) {
                    mMainHandler.post(() -> onSocketConnectFail(e));
                }
            }
        }

        private void disconnect() {
            Log.d(TAG, "Disconnecting");

            mConnected = false;

            if (mBufferedWriter != null) {
                try {
                    mBufferedWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                mBufferedWriter = null;
            }

            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                mSocket = null;
            }
        }

        private void sendMessage(String message) {
            Log.d(TAG, "Sending message");

            if (mConnected && !mStop && mBufferedWriter != null) {
                try {
                    mBufferedWriter.write(message);
                    mBufferedWriter.newLine();
                    mBufferedWriter.flush();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to send message");

                    e.printStackTrace();
                }
            }
        }

        private class ReaderThread extends Thread {
            private static final String TAG = "ConnectionReaderThread";

            private BufferedReader mBufferedReader;

            ReaderThread(String name, BufferedReader pBufferedReader) {
                super(name);

                mBufferedReader = pBufferedReader;
            }

            @Override
            public void run() {
                Log.d(TAG, "Thread started");

                Exception socketException = null;

                try {
                    while (mConnected && !mStop) {
                        String message = mBufferedReader.readLine();

                        if (message == null) {
                            break;
                        }

                        if (!mStop) {
                            mMainHandler.post(() -> onSocketMessage(message));
                        }
                    }
                } catch (SocketException e) {
                    socketException = e;
                } catch (IOException e) {
                    Log.e(TAG, "Read exception: " + e.getMessage());
                }

                if (mBufferedReader != null) {
                    try {
                        mBufferedReader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    mBufferedReader = null;
                }

                if (mConnectionHandler != null) {
                    Message exitMessage = Message.obtain(
                            mConnectionHandler,
                            Constants.MESSAGE.READ_THREAD_EXIT,
                            socketException);

                    mConnectionHandler.sendMessage(exitMessage);
                }

                Log.d(TAG, "Exiting thread");
            }
        }
    }
}
