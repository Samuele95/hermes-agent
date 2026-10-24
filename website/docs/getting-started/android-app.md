---
sidebar_position: 4
title: "Android app"
description: "Run Hermes Agent as a native Android app: the same Python core, embedded, with a phone-sized shell"
---

# Hermes as an Android app

:::caution Preview
The Android app is a preview. There is no published APK yet: you build it from this repository.
For a terminal install on Android, see [Android / Termux](./termux.md).
:::

The app runs the same Python core as the desktop, inside the app's own process. A small Kotlin
shell provides the screens, notifications and Android's secure key storage. Your memory, skills and
sessions live in the app's private storage, as they live in `~/.hermes` on a desktop.

## What you need

- An **arm64** (`arm64-v8a`) phone running Android 7.0 (API 24) or newer.
- About **300 MB** of free storage once installed. The APK carries Python 3.14, the core and its
  wheels.
- A model to talk to. Use one of these:
  - a provider key;
  - a [Nous Portal](https://portal.nousresearch.com) account;
  - any OpenAI-compatible endpoint the phone can reach (for example Ollama on your computer).

## Build and install

The APK is assembled in two steps. First the runtime payload, then the app around it.

1. **The wheel set.** Build it with the Termux recipe on an arm64 Linux host, as CI does for the
   Termux package. Run `scripts/termux/build_cpython.sh`, `scripts/termux/stage_runtime_libs.py` and
   `scripts/termux/termux_build.sh`. The result is a `wheelhouse/` directory and a `runtime-libs/lib`
   directory.
2. **Python for Android.** Download python.org's Android build of CPython 3.14.7
   (`python-3.14.7-aarch64-linux-android.tar.gz`) and verify its signature as python.org describes.
3. **The payload.** From the repository root, run:

   ```bash
   python -m scripts.android.payload \
     --python-tarball python-3.14.7-aarch64-linux-android.tar.gz \
     --python-sha256 <the tarball's sha256> \
     --wheelhouse <out>/wheelhouse --runtime-libs <out>/runtime-libs/lib \
     --out apps/android/payload
   ```

   The assembler checks the tarball's sha256. It swaps in the recipe's SQLite, which has FTS5 for
   session search, and writes `payload.json` with a build id that changes with any input.
4. **The APK.** With the Android SDK and NDK installed, run:

   ```bash
   cd apps/android && ./gradlew :app:assembleDebug
   ```

   Then install `app/build/outputs/apk/debug/app-debug.apk` with
   `adb install -r`.

The first start unpacks the runtime into the app's storage, which takes a few seconds. Later starts
reuse it.

## First run: choose a model

The app opens on its setup screen when no model is configured. You can return to it at any time with
**Model** in the chat.

- **A provider key.** Pick a provider from the list the core offers and paste its key. The key goes
  to Android's keystore. It never lands in a file. To remove it, open the provider again and tap
  **Forget the … key on this phone**.
- **Nous Portal.** Sign in with a device code. The app opens the portal page, and you approve there.
- **A custom endpoint.** Give it a name, the base URL (for example
  `http://192.168.1.10:11434/v1` for Ollama), a model and an optional key.

## What works on the phone

- **Chat.** Replies stream in, with the model's reasoning folded under each reply. **Stop** ends a
  reply the model is still writing. **History** lists past conversations; pick one to continue it. A
  notification keeps a running reply alive while the app is in the background.
- **Notifications.** While the app is not on screen, a question from the agent and a finished reply
  each show a notification; tapping it opens the chat. On the lock screen they say only that Hermes
  asks or replied.
- **Questions from the agent.** Clarifying questions, command approvals and requests for a secret
  appear as cards. A secret you type goes to the keystore, not into the conversation.
- **Memory, skills and sessions.** They persist across restarts. Session search uses FTS5.
- **Bundled skills.** The skills that list Linux are offered. macOS-only skills are hidden.
- **A remote terminal over SSH.** Tap **Terminal** to point the agent's terminal at a host.
  1. The app creates its own Ed25519 key and keeps it in the keystore.
  2. It shows the host's SHA256 fingerprint, and pins it only after you confirm.
  3. It shows its public key, for you to add to the host's `~/.ssh/authorized_keys`.
  4. **Check login** confirms the setup works.

  The connection uses a Python SSH client, so the phone needs no OpenSSH.

- **Command approvals.** **Settings** chooses who approves a command the agent wants to run when
  Hermes flags it as risky: **Ask me** every time, **Smart** (a second model approves clearly safe
  commands, refuses clearly destructive ones and asks you about the rest), or **Never ask**.

Not available in the app: running code locally (`execute_code`) and the local browser tools. The
app cannot start a second Python process, and it ships no browser engine.

## Always-on gateway (optional)

The gateway keeps Hermes reachable from your own Telegram bot and runs your scheduled jobs while the
app is closed. It is off until you turn it on with **Gateway** in the chat.

- It runs in a separate process as a foreground service, with a notification that has a **Stop**
  action. If Android ends the process, the gateway starts again by itself.
- **Telegram.** Create a bot with [@BotFather](https://t.me/BotFather) and paste its token. Then
  enter your numeric Telegram user id ([@userinfobot](https://t.me/userinfobot) tells you yours).
  Only that user may talk to the bot. Give the phone its own bot: two gateways polling one token
  conflict. A running gateway picks up a changed token or user at its next start: tap **Restart** on
  the gateway screen. If Telegram cannot be reached while you save, the token is kept and the screen
  says it could not be checked; a token Telegram refuses is removed again.
- **Scheduled jobs.** Ask in a chat (for example "every morning at 8, summarize my calendar"). The
  gateway runs them.
- **Battery.** When you turn the gateway on, Android asks whether Hermes may ignore battery
  optimization. Allow it for the most reliable connection. On Samsung phones, also add Hermes to
  **Settings › Battery › Background usage limits › Never sleeping apps**.
- **Start when the phone starts** is a separate, opt-in switch.

## Your data and keys

- Provider keys, the SSH key and the bot token are kept in Android's keystore. The core reads them
  as environment variables only. The app never writes them to `.env` or to any other file.
- Nous Portal sign-in tokens live where the core keeps them on every platform. That is the profile's
  `auth.json`, inside the app's private storage.
- Android backup is off for the app. Uninstalling the app deletes its data.

## Troubleshooting

- **"Provider temporarily unavailable" on every message.** The phone cannot reach the endpoint.
  Check that the URL is reachable from the phone, not only from your computer. During development,
  `adb reverse tcp:11434 tcp:11434` lets the phone use an Ollama on the computer as
  `http://127.0.0.1:11434/v1`.
- **A dialog about 16 KB page sizes on start.** Debug builds on Android 15 and later show it. Four
  optional image libraries are 4 KB-aligned. On a phone with 16 KB pages, WebP images cannot be
  decoded, and everything else works.
- **The gateway stops overnight.** Allow the battery exemption, and on Samsung add Hermes to the
  never-sleeping apps (see above).
