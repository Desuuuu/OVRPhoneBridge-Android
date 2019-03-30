# OVRPhoneBridge-Android

Official Android client for [OVRPhoneBridge](https://github.com/Desuuuu/OVRPhoneBridge).

## Screenshots
<img src="/screenshots/main.png" width="300" /> <img src="/screenshots/settings.png" width="300" />

## Features
* Notification mirroring
* Send and receive SMS

## Permissions overview
* General networking
  * [INTERNET](https://developer.android.com/reference/android/Manifest.permission.html#INTERNET)
  * [ACCESS_NETWORK_STATE](https://developer.android.com/reference/android/Manifest.permission#ACCESS_NETWORK_STATE)

* Persistent service
  * [FOREGROUND_SERVICE](https://developer.android.com/reference/android/Manifest.permission#FOREGROUND_SERVICE)
  * [WAKE_LOCK](https://developer.android.com/reference/android/Manifest.permission#WAKE_LOCK)

* Notification mirroring
  * [BIND_NOTIFICATION_LISTENER_SERVICE](https://developer.android.com/reference/android/Manifest.permission#BIND_NOTIFICATION_LISTENER_SERVICE)

* Send and receive SMS
  * [READ_SMS](https://developer.android.com/reference/android/Manifest.permission#READ_SMS)
  * [SEND_SMS](https://developer.android.com/reference/android/Manifest.permission#SEND_SMS)
  * [READ_CONTACTS](https://developer.android.com/reference/android/Manifest.permission#READ_CONTACTS)
  * [READ_PHONE_STATE](https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_STATE) (required to send SMS on some Android versions)

## Libraries
* [Guava](https://github.com/google/guava)
* [libsodium-jni](https://github.com/joshjdevl/libsodium-jni)
* [libphonenumber-android](https://github.com/MichaelRocks/libphonenumber-android)

## License
[GPLv3](http://www.gnu.org/licenses/gpl-3.0.html)
