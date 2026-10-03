# Android Bridge

A small native Android companion for the Universal Android + Chrome Bridge.

## What it provides
- Explicit ON/OFF control.
- Emergency stop.
- Local-only HTTP command endpoint bound to 127.0.0.1.
- One-time pairing token generated on the device.
- Allowlisted packages and browser domains.
- Android AccessibilityService for visible UI actions when the user enables it.
- Chrome handoff through Android intents.
- No password, cookie, token, or screen-secret collection.

## Important limitation
The APK does not by itself give ChatGPT direct network access to the phone. A separate authorized relay/connector is required for ChatGPT to send commands to this local bridge.

## Build
GitHub Actions builds a debug APK with Gradle.
