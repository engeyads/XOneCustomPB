# XOne Custom Controller Bridge (TabletGamepadBridge)

[![Android](https://img.shields.io/badge/Android-11%2B-brightgreen.svg)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%20%2F%20C%2B%2B-orange.svg)](https://kotlinlang.org/)

An advanced, **rootless Android gamepad driver and bridge** that brings full system-wide support for unofficial/clone 2.4GHz Xbox Wireless controllers to Android tablets and phones.

---

## 🚀 Overview

Many third-party 2.4GHz Xbox-style wireless controllers use Microsoft's proprietary **GIP (Gaming Input Protocol)**. Android natively ignores or misinterprets these dongles (VID `0x045E`, PID `0x02EA`). 

**XOne Custom PB** communicates with these dongles directly over Android's USB Host API, parses raw GIP input streams, and maps them into a real Linux kernel virtual input device (`/dev/uhid`). The result is **native, zero-latency, system-wide controller support** across all Android games (e.g., *Minecraft*, *ROBLOX*, emulator suites, etc.) — **no root required**.

---

## ✨ Key Features

- **🎮 Full GIP Protocol Implementation**:
  - Implements the GIP handshake sequence: `ANNOUNCE` (0x02), `IDENTIFY` (0x04), `POWER ON` (0x05), `RUMBLE` (0x09), `LED` (0x0A), and `AUTHENTICATE` (0x06).
  - Handles independent `VIRTUAL_KEY` (0x07) Guide/Home button events.
  - Fixes stick sign inversion and axis rescaling issues cleanly without 100% deflection integer wraparound.

- **🎯 Precise Android HID Axis Alignment**:
  - Mapped specifically to Android's native input conventions (`AXIS_Z` / `AXIS_RZ` for right stick; `Simulation Controls` `AXIS_GAS` / `AXIS_BRAKE` for triggers).
  - Eliminates "trigger acts as camera stick" bugs common in desktop/SDL-centric HID descriptors.

- **📱 Self-Contained On-Device ADB (No PC Required)**:
  - Built-in Wireless Debugging engine with **BoringSSL C++ JNI bindings** for the SPAKE2 password-authenticated key exchange.
  - Pure-Kotlin ADB wire client for RSA TLS reconnects.
  - **One-time 6-digit setup**: After pairing once, the app automatically reconnects across reboots with a single tap.

- **⚡ SELinux Domain Crossing via Internal APIs**:
  - Crosses the `untrusted_app` to `shell` SELinux boundary safely using hidden `IContentProvider.call()` IPC mechanisms, matching Shizuku's proven architecture.
  - Self-healing background loop automatically restores service state if the app process restarts.

---

## 🏗️ Architecture Summary

```
┌────────────────────────────────────────────────────────┐
  USB Dongle (Xbox 2.4GHz GIP Protocol / USB Host API)
└───────────────────────────┬────────────────────────────┘
                            │ Raw GIP Packets
                            ▼
┌────────────────────────────────────────────────────────┐
  UsbGamepadReader.kt (GIP Parser & Rescaler)
└───────────────────────────┬────────────────────────────┘
                            │ Standardized HID Report
                            ▼
┌────────────────────────────────────────────────────────┐
  GamepadHidReport.kt (15-Byte Android HID Layout)
└───────────────────────────┬────────────────────────────┘
                            │ UHID Input Commands
                            ▼
┌────────────────────────────────────────────────────────┐
  PrivilegedMain (Runs as Shell UID via app_process)
                            │ Writes to /dev/uhid
                            ▼
            Virtual Kernel Gamepad Device
```

---

## 🛠️ Tech Stack & Requirements

- **Target SDK**: Android 14 (API 34) | **Min SDK**: Android 11 (API 30)
- **Languages**: Kotlin (1.9.24), C++17 (NDK 27)
- **Libraries**:
  - `BoringSSL` (SPAKE2 crypto for ADB Wireless Pairing)
  - `hiddenapibypass` & hidden stub APIs
  - `BouncyCastle` (X.509 certificates)
  - Android USB Host API & UHID subsystem

---

## 📦 Building from Source

1. **Prerequisites**:
   - Android Studio Ladybug or newer
   - Android NDK `27.0.12077973`
   - CMake `3.22.1`

2. **Clone & Build**:
   ```bash
   git clone https://github.com/engeyads/XOneCustomPB.git
   cd XOneCustomPB
   ./gradlew assembleDebug
   ```

3. **Deploy to Device**:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📲 Setup Guide

### One-Time Setup (Wireless Debugging Pairing)
1. Enable **Developer Options** on your Android device.
2. Turn on **Wireless Debugging**.
3. Open **XOne Custom PB** on your device and tap **Connect Controller**.
4. If prompted for setup, tap **Pair device with pairing code** in Wireless Debugging settings, and enter the 6-digit pairing code shown on screen.
5. Plug your 2.4GHz USB dongle into your Android device via an OTG adapter and tap **Connect Controller**.

---

## 📄 License

This project is licensed under the [GPL-3.0 License](LICENSE).  
*GIP protocol logic adapted from open-source drivers (xone). ADB wireless pairing architecture adapted from Shizuku.*
