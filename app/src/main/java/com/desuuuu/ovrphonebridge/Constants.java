package com.desuuuu.ovrphonebridge;

public class Constants {
    public static final String PREFERENCES_NAME = "preferences";
    public static final String FEEDBACK_URL = "https://github.com/Desuuuu/OVRPhoneBridge-Android";
    static final long TIMESTAMP_LEEWAY = 180;
    static final int SOCKET_TIMEOUT = 20000;
    static final int MAX_RETRY = 15;
    static final int HANDSHAKE_TIMEOUT = 15000;
    static final int HANDSHAKE_PROMPT_TIMEOUT = 120000;
    static final int SMS_LIST_MAX = 20;
    static final int SMS_PER_PAGE = 30;

    public interface INTENT {
        String START_CONNECTION_SERVICE = "com.desuuuu.ovrphonebridge.intent.start_connection_service";
        String STOP_CONNECTION_SERVICE = "com.desuuuu.ovrphonebridge.intent.stop_connection_service";
        String DISCONNECT_CONNECTION_SERVICE = "com.desuuuu.ovrphonebridge.intent.disconnect_connection_service";
        String HANDSHAKE_RESPONSE = "com.desuuuu.ovrphonebridge.intent.handshake_response";

        String START_NOTIFICATION_SERVICE = "com.desuuuu.ovrphonebridge.intent.start_notification_service";

        String STATUS_REQUEST = "com.desuuuu.ovrphonebridge.intent.status_request";
        String STATUS_NOTIFICATION = "com.desuuuu.ovrphonebridge.intent.status_notification";

        String DISMISS_HANDSHAKE_PROMPT = "com.desuuuu.ovrphonebridge.intent.dismiss_handshake_prompt";

        String NOTIFICATION_LIST_REQUEST = "com.desuuuu.ovrphonebridge.intent.notification_list_request";
        String NOTIFICATION_LIST = "com.desuuuu.ovrphonebridge.intent.notification_list";
        String NOTIFICATION_RECEIVED = "com.desuuuu.ovrphonebridge.intent.notification_received";
        String NOTIFICATION_REMOVED = "com.desuuuu.ovrphonebridge.intent.notification_removed";
        String NOTIFICATION_DISMISS = "com.desuuuu.ovrphonebridge.intent.notification_dismiss";

        String SMS_SENT = "com.desuuuu.ovrphonebridge.intent.sms_sent";
    }

    public interface NOTIFICATION {
        String CHANNEL_CONNECTION_SERVICE = "com.desuuuu.ovrphonebridge.notification.CHANNEL_CONNECTION_SERVICE";
        String CHANNEL_HANDSHAKE = "com.desuuuu.ovrphonebridge.notification.CHANNEL_HANDSHAKE";
        int ID_CONNECTION_SERVICE = 3981;
        int ID_HANDSHAKE = 5643;
        int SERVICE_CHECK_INTERVAL = 900000;
        int MAX_TITLE_LENGTH = 40;
        int MAX_TEXT_LENGTH = 250;
    }

    public interface SERVICE {
        int STATUS_STOPPED = 0;
        int STATUS_DISCONNECTED = 1;
        int STATUS_CONNECTING = 2;
        int STATUS_CONNECTED = 3;
    }

    public interface WAKELOCK {
        String TAG = "OVRPhoneBridge::ConnectionWakeLock";
    }

    public interface MESSAGE {
        int CONNECT = 0;
        int DISCONNECT = 1;
        int MESSAGE = 2;
        int READ_THREAD_EXIT = 3;
    }

    public interface PERMISSION {
        int SMS_REQUEST = 1;
    }

    public interface DEFAULT {
        String PORT = "8888";
        boolean RETRY_FOREVER = false;
        boolean FEATURE_NOTIFICATIONS = false;
        boolean FEATURE_SMS = false;
        String DEVICE_NAME = "Android Device";
    }
}
