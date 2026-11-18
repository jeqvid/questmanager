# Quest Manager

Tweak hidden Meta Quest settings (refresh rate, swap interval, resolution, recording,
experimental compositor options, proximity sensor) right from the headset.

- **Quest 1:** needs root (Magisk / Singularity). Grant Quest Manager root in Magisk.
- **Quest 2, 3, 3S, Pro:** no root needed. Quest Manager connects to the headset's own
  **wireless debugging** (pair once in the WIRELESS ADB tab).

Download the latest APK from [Releases](https://github.com/jeqvid/questmanager/releases).

## Wireless ADB setup (Quest 2 and newer)

1. Connect the headset to Wi-Fi.
2. In Quest Manager open the **WIRELESS ADB** tab and tap **OPEN ANDROID SETTINGS**.
3. Go to *System > Developer options > Wireless debugging* and turn it on.
4. Tap *Pair device with pairing code*, type the code and port into Quest Manager, tap **PAIR**.
5. Type the port shown under *IP address & Port* and tap **CONNECT** (or leave it empty to auto-detect).

Pairing is remembered. After a reboot, turn Wireless debugging back on; Quest Manager reconnects by itself.

## Building

Every push builds the APK with GitHub Actions and publishes it as a GitHub release named after
`versionName` in `app/build.gradle` (the tag is created automatically).

Optional signing (so updates install over older versions): add these repository secrets:
`KEYSTORE_BASE64` (base64 of your .jks), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the APK is signed with a debug key.

Local build: `gradle assembleRelease` (Android SDK + JDK 17).
