# Tablet Gamepad Bridge (XOne Custom PB) — Final Architecture & Publishing Summary

**Status: Complete, tested live on Lenovo Tab M9 & Samsung Galaxy A52, built for production release (`app-release.aab`).**

## Google Play Console & Monetization Details
- **Google Play Developer Account ID**: `6557237174445003252` (`eas2012@gmail.com`)
- **GitHub Repository**: [https://github.com/engeyads/XOneCustomPB](https://github.com/engeyads/XOneCustomPB)
- **Production App Bundle (.aab)**: `app/build/outputs/bundle/release/app-release.aab`
- **In-App Purchase Product ID**: `remove_ads_5usd` ($5.00 USD One-Time Purchase / Remove Ads)
- **AdMob Account**: `eas2012@gmail.com` (15s usage -> 3 Interstitial Ads queue loop)

---

## Goal
Get an unofficial 2.4GHz Xbox-style wireless controller (uses Microsoft's proprietary GIP protocol, VID `0x045E`, PID `0x02EA`) working as a **real, system-wide recognized gamepad** on Android tablets and phones without root.

---

## Key Architecture & Features

1. **`UsbGamepadReader.kt`** — talks to the controller dongle directly via Android's USB Host API. Implements Microsoft's **GIP (Gaming Input Protocol)** handshake sequence (`ANNOUNCE`, `IDENTIFY`, `POWER ON`, `RUMBLE`, `LED`, `AUTHENTICATE`).
2. **`GamepadHidReport.kt`** — converts parsed GIP state into a 15-byte HID report matching Android's input conventions (`AXIS_Z`/`AXIS_RZ` for right stick; `Simulation Controls` `AXIS_GAS`/`AXIS_BRAKE` for analog triggers).
3. **`GamepadVisualizerView.kt`** — interactive 2D Canvas rendering of the custom blue controller faceplate with Mars (♂) and Lightning (⚡) pattern graphics, highlighting pressed buttons and moving stick caps live.
4. **Pad Link Dashboard & Metrics**: Real-time display of battery (`78%`), latency (`8 ms`), polling rate (`250 Hz`), stick coordinates (`+0.00 , +0.00`), analog trigger percentages, and stable deadzone calibration (`27%`).
5. **Sticky Bottom Action Bar**: Full-width action buttons (**Recenter sticks**, **Connect**, **Dev Options**, **Agreement**) stuck to the bottom of the screen, ensuring instant visibility on all display sizes without requiring scrolling.
6. **Pre-authenticated `iyads@IYAD` Key**: Uses the computer's trusted RSA key pair (`~/.android/adbkey`) so Wireless Debugging connects instantly across reboots without asking for 6-digit codes.
7. **`FloatingPairingOverlayService.kt`**: Floating overlay window that floats over System Settings on tablets/devices that disable split-screen mode, allowing simultaneous 6-digit code entry.
8. **`AdManager.kt` & `BillingManager.kt`**:
   - Free Users: Preloads and plays a 3-ad series on launch and connect, followed by 30 seconds of free usage time between series.
   - Pro Users ($5 USD One-Time Purchase): Permanently disables all ads, hides ad banners, and transforms the top-left button into a gold `⭐ PRO` badge.

---

## Release Build & Deploy Command
- Generate Release Bundle:
  ```powershell
  Remove-Item Env:\ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
  .\gradlew.bat :app:bundleRelease
  ```
- Output Location:  
  `C:\Users\iyads\Projects\tablet-gamepad-bridge\app\build\outputs\bundle\release\app-release.aab`
