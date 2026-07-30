# Privacy Policy — GameMapperMind

**Last updated**: 2026-07-15

## 1. Introduction

GameMapperMind ("we", "our", or "the app") is an open-source gamepad keymapper application for Android. This Privacy Policy explains how we handle your data.

**Core principle**: We collect NO personal data. Your privacy is our priority.

## 2. Data We Do NOT Collect

GameMapperMind does **NOT** collect, store, or transmit:

- Personal information (name, email, phone number)
- Device identifiers (IMEI, Android ID, advertising ID)
- Location data
- Contacts, messages, or call logs
- Photos, videos, or files
- Browsing history
- Account credentials
- Gameplay data or game account info

## 3. Permissions Used

| Permission | Purpose | Data Collected |
|------------|---------|----------------|
| SYSTEM_ALERT_WINDOW | Display overlay button mapping on top of games | None |
| FOREGROUND_SERVICE | Keep gamepad listener active in background | None |
| POST_NOTIFICATIONS | Show service notification | None |
| FOREGROUND_SERVICE_SPECIAL_USE | Gamepad mapping service | None |

## 4. Local Data Storage

Data stored locally on your device only (never transmitted):

- Profiles: gamepad mapping configurations (encrypted AES-256-GCM)
- Macros: recorded button sequences (encrypted)
- Settings: app preferences
- Logs: diagnostic logs (in-app only)

To delete all local data: Uninstall the app or use "Reset All" in settings.

## 5. Shizuku Integration

App uses Shizuku (shell uid 2000) for touch injection. Shizuku runs as a separate app. We do NOT communicate with Shizuku servers. Permission can be revoked anytime.

## 6. Touch Injection

All touch events use TOOL_TYPE_FINGER with SOURCE_TOUCHSCREEN. Processed locally. NOT sent to any server.

## 7. Third-Party Services

GameMapperMind does NOT use any third-party services: no analytics, no crash reporting, no ads, no cloud services.

## 8. Open Source

Open-source under Apache-2.0 license. Source: https://github.com/NanoMindExplorer/GameMapperMind

## 9. Contact

Email: privacy@nanomindexplorer.com
GitHub Issues: https://github.com/NanoMindExplorer/GameMapperMind/issues

By using GameMapperMind, you agree to this Privacy Policy.
