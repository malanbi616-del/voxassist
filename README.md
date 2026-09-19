# VoxAssist — voice assistant starter (built entirely from your phone)

You edit this on your phone with Termux; GitHub Actions compiles the actual
APK in the cloud. Your A51/Y58 never has to run a heavy Android build.

## One-time setup in Termux
```
pkg update && pkg install git -y
git config --global user.name "you"
git config --global user.email "you@example.com"
```

## Get the code onto your phone
1. In Chrome, go to github.com/new and create an empty repo, e.g. `voxassist`.
2. In Termux:
   ```
   git clone https://github.com/<you>/voxassist.git
   cd voxassist
   ```
3. Copy in this project's files, matching the folder layout below (Termux's
   `nano` or `vim` work fine for editing text files).
4. ```
   git add .
   git commit -m "initial scaffold"
   git push
   ```

## Folder structure
```
voxassist/
  build.gradle.kts
  settings.gradle.kts
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/java/com/example/voxassist/MainActivity.kt
    src/main/res/layout/activity_main.xml
  .github/workflows/build.yml
```

## Getting the APK
Every push to `main` triggers GitHub Actions to build a debug APK.
- On GitHub: your repo → **Actions** tab → latest run → **Artifacts** →
  download `voxassist-debug-apk`
- Open the downloaded `.apk` on your phone (allow "install from this
  source" when prompted) to install it

## Getting an AI key
The app calls Gemini to decide what to do with each command. Get a free key
at aistudio.google.com/apikey — works from your phone browser, no PC
needed — then paste it into VoxAssist's settings field.

## What v0.1 can do
Voice command → Gemini picks an action → app fires the matching Android
intent: dial a number, send a text, open an installed app, web search,
navigate in Maps, set an alarm. Anything else, it just replies out loud.

## What it can't do yet
- Multi-step chains ("text mom, then set an alarm") — v0.1 does one action
  per command
- Reaching *inside* a third-party app's UI (tapping specific buttons in an
  app that has no API for it) — that needs a visible, disclosed
  Accessibility Service, which is a bigger, separate step
- Always-listening / wake word — currently push-to-talk only

## Natural next steps
- Give it short-term memory (send the last few turns to Gemini)
- Add a wake word (e.g. Porcupine)
- Add more actions: calendar events, media controls, flashlight, timers
