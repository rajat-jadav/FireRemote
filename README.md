# Fire Remote

Control an Amazon Fire TV Stick from your Android phone:

| # | Feature | Phone side | TV side |
|---|---------|-----------|---------|
| 1 | Mouse / pointer | Touchpad: drag, tap, hold, two-finger scroll | On-screen pointer, taps and swipes injected |
| 2 | Quick start apps | Apps tab: tap to launch, star to pin favourites | Lists installed apps, launches them |
| 3 | Move pad | D-Pad tab: arrows + OK (hold to repeat) | Moves focus between on-screen items |
| 4 | Volume | Vol -, Mute, Vol + buttons and the phone's own volume keys | Changes volume |

Back and Home buttons are always visible at the bottom. A two-finger tap on the touchpad also means Back.

## Project layout

```
FireRemote/
  tvreceiver/        Fire TV app (Kotlin, no dependencies)
    RemoteAccessibilityService.kt   pointer overlay, gestures, focus moves, volume, app launch
    CommandServer.kt                TCP server on port 8765, PIN check, command parser
    MainActivity.kt                 status screen: IP address + pairing PIN
    CursorView.kt, Prefs.kt, Net.kt
  phonecontroller/   Phone app (Kotlin, Material 3)
    MainActivity.kt                 connect screen, tabs, buttons
    TouchpadView.kt                 gesture detection
    RemoteClient.kt                 TCP client with ping and reconnect handling
    AppsAdapter.kt                  quick start list with pinning
  scripts/install_to_firetv.sh      one-command install + enable over ADB
```

## How it works (useful for the viva)

```
Phone (touch)  --TCP, one text line per command-->  Fire TV
                                                    CommandServer
                                                      -> RemoteAccessibilityService
                                                           dispatchGesture()    taps / swipes
                                                           TYPE_ACCESSIBILITY_OVERLAY  pointer drawing
                                                           AccessibilityNodeInfo.focusSearch()  D-pad
                                                           performGlobalAction()  Back / Home
                                                           AudioManager         volume
                                                           PackageManager       app list + launch
```

Why an Accessibility Service? A normal Android app cannot draw over other apps or tap on other apps' screens. An accessibility service is the supported way to do both without root. Apps such as Mouse Toggle use the same approach on Fire TV.

Protocol (plain text, one command per line):

```
Phone -> TV:  HELLO <pin>
              MOVE dx dy              CLICK        LONG
              SWIPE dxPermille dyPermille
              DPAD UP|DOWN|LEFT|RIGHT|OK
              BACK | HOME | RECENTS
              VOL UP|DOWN|MUTE
              LAUNCH <package>        APPS         PING
TV -> Phone:  OK 1 | DENY | APP<TAB>package<TAB>label ... END | PONG
```

Security: the TV only accepts commands after `HELLO <6-digit PIN>`. Five wrong PINs lock pairing for 60 seconds. The PIN is shown only on the TV screen and can be regenerated with the "New PIN" button. Traffic is not encrypted, so use it on your home Wi-Fi.

## Build

1. Install Android Studio and open the `FireRemote` folder.
2. Let Gradle sync (it downloads what it needs; Android Studio may offer to upgrade the Android Gradle Plugin, which is fine).
3. Build > Build APK(s) for both modules, or run in a terminal after Studio has generated the wrapper:
   `./gradlew :tvreceiver:assembleDebug :phonecontroller:assembleDebug`

APKs end up in `tvreceiver/build/outputs/apk/debug/` and `phonecontroller/build/outputs/apk/debug/`.

## Install on the Fire TV

On the Fire TV: Settings > My Fire TV > Developer Options > turn ADB debugging ON. Note the IP address under Settings > My Fire TV > About > Network.

Then, from your computer on the same Wi-Fi:

```
./scripts/install_to_firetv.sh 192.168.1.50
```

(Replace the IP with your Fire TV's address.) The script installs the APK, enables the accessibility service, allows background app launching and opens the app. Accept the "Allow USB debugging?" prompt on the TV the first time.

Manual alternative, if you prefer typing the commands yourself:

```
adb connect 192.168.1.50:5555
adb install -r tvreceiver/build/outputs/apk/debug/tvreceiver-debug.apk
adb shell settings get secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services <existing value>:com.rajat.fireremote.tv/com.rajat.fireremote.tv.RemoteAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell appops set com.rajat.fireremote.tv SYSTEM_ALERT_WINDOW allow
```

(If the first command prints `null`, use just the service name without a prefix. Keeping the existing value avoids switching off other accessibility services such as VoiceView.)

## Install on the phone and pair

1. Install `phonecontroller-debug.apk` on your Android phone (allow installs from your file manager or run it from Android Studio).
2. Open **Fire Remote** on the Fire TV (Your Apps & Channels). It shows `Service is running`, the IP address and a 6-digit PIN.
3. Open the phone app, type the IP and PIN, tap **Connect**. The phone remembers them and reconnects automatically next time.

## Using it

- **Touchpad**: drag to move the pointer, tap to click, hold for long press, drag with two fingers to scroll or swipe, tap with two fingers for Back. The Speed slider changes pointer sensitivity. The pointer hides after 6 seconds without movement.
- **D-Pad**: arrows move the focus highlight, OK selects. Hold an arrow to repeat.
- **Apps**: tap an app to launch it. Tap the star to pin it; pinned apps stay at the top.
- **Volume**: Vol -, Mute, Vol + on screen. While the phone app is open and connected, the phone's physical volume keys control the TV instead of the phone.

## Known limits and troubleshooting

- **Needs Fire OS 6 or newer (API 24+).** The 2nd-generation Fire TV Stick (Fire OS 5) cannot inject gestures. 3rd-gen, Lite, 4K, 4K Max and Fire TV Cube are fine.
- **D-Pad is emulated.** Android does not let an accessibility service press real remote keys, so the D-Pad moves focus through the screen's accessibility tree and clicks the focused item. Standard menus and grids work well. Some video players and games draw their own controls and may ignore it; use the touchpad there.
- **Volume depends on HDMI-CEC.** The stick usually forwards volume to the TV or soundbar through HDMI-CEC. Turn on "HDMI CEC Device Control" in Fire TV Settings > Display & Sounds > HDMI CEC Device Control and "CEC" on the TV (names vary by brand). If the on-screen volume bar moves but the sound does not change, CEC is the cause.
- **App launch does nothing (newer Fire OS).** Run `adb shell appops set com.rajat.fireremote.tv SYSTEM_ALERT_WINDOW allow` (the install script does this).
- **"Service is OFF" on the TV.** Re-run the `settings put secure` commands above, or look for Fire Remote Receiver in Settings > Accessibility. Some Fire OS versions hide third-party services there, which is why the ADB route is the reliable one.
- **Phone says "Timed out".** Check the IP, and that phone and TV are on the same Wi-Fi (not a guest network with client isolation).
- **Phone says "Wrong PIN".** Look at the PIN on the TV again; "New PIN" changes it.
- **Pointer feels slow or fast.** Use the Speed slider.

## Ideas to extend

Automatic discovery of the TV (Android NSD / mDNS) instead of typing the IP; app icons in the Apps tab; a text-entry box that types into the focused field with `ACTION_SET_TEXT`; drag-and-drop gestures; a Wear OS or iOS controller (the protocol is plain text, so any client can speak it).
