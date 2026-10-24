# Screen Agent (Android)

An AI that sees your phone screen, talks to you, listens, and taps / swipes / types / opens apps.
This is a complete Gradle project (no Android Studio setup needed to build it).

## Get the APK without Android Studio (GitHub, works from a phone)
1. Make a free GitHub repo and upload everything in this folder (keep the .github folder).
2. Open the repo's Actions tab -> "Build APK" -> Run workflow (it also runs on every upload).
3. When it finishes (~5 min), open the run -> Artifacts -> download "ScreenAgent-APK" (a zip containing app-debug.apk).
4. Unzip, install the APK (allow "install unknown apps" for your browser/files app).

## Or build with Android Studio
Open this folder as a project, then Build > Build APK(s). Output: app/build/outputs/apk/debug/app-debug.apk

## Use
1. Open the app, paste your Anthropic API key.
2. Tap "Turn on Accessibility access" -> Screen Agent -> On.
   (Android 13+ sideloaded apps: App info -> top-right menu -> "Allow restricted settings" first.)
3. Type or speak a goal, tap Start. The app goes to the background and the AI takes over.
4. A red STOP button floats on screen. Tap it anytime.

## Notes
- Needs Android 11+.
- It asks (out loud) before sending, buying, deleting, etc. Answer by voice.
- Screens with FLAG_SECURE (banking apps, some password screens) appear black to it.
- The API key is stored on the phone. If you distribute the app, route requests through your own server instead.
