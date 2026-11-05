# Dot Music

**Your music, as a spinning record of dots.**

A home-screen music widget and full-screen player in a dot-matrix style inspired by Nothing. The album cover becomes a record made of hundreds of dots: brighter parts get bigger dots, like a halftone print.

[**⬇ Download the latest APK**](../../releases/tag/latest) · Android 8.0+ · No internet permission

## Widgets

| Size | What's on it |
| --- | --- |
| **2×2 record** | The cover as a dot record, a comet of dots orbiting it, rings of dots rippling out of the play button. |
| **4×2 player** | Dot record with its comet, the title set in dots, a live dot equaliser, a dot progress bar, the clock, previous / play / next. |
| **4×1 strip** | Live dot equaliser, title in dots, play and next. |

The equaliser, the orbiting comet and the ripples are animated vector drawables that your launcher plays by itself, so they stay smooth and cost almost no battery. They run only while music plays. The progress bar steps forward every few seconds, and only while the screen is on.

## The full-screen player

Tap a widget's record or title, or open it from the app.

- **A record of ~700 springy dots** that slowly spins while the song plays and settles when you pause.
- **Ripples on the beat** roll out through the record, and a **ring of dots** around it works as a circular equaliser.
- **React to the real beat (optional).** The player follows the actual music using Android's audio visualizer. Android files this under the microphone permission, but it only measures loudness per frequency and never records anything. When it's off, a steady pulse plays instead.
- **Play with it.** Drag your finger through the dots to push them aside. Tap the record to pause, or flick it left or right to skip: the record bursts apart and the dots reassemble into the next cover.
- **Title in dots** that rains in column by column and scrolls when it's long. Drag the dot progress bar to seek.
- Colour dots from the cover, or **white dots** in Nothing style. Respects Android's "remove animations" setting.

## Setup

1. Install the APK from [Releases](../../releases/tag/latest).
2. Open Dot Music and tap **Allow notification access**. This is how Android lets an app see what's playing.
3. Tap **Add the 2×2 record**, **Add the 4×2 player** or **Add the 4×1 strip** to drop a widget on your home screen.

Works with any music app that shows media controls: Spotify, YouTube Music, Apple Music, PixelPlayer and others.

## Privacy

No internet permission, no accounts, no analytics. The song, cover and play state are read from your music app's media session and kept in memory only.

## Build

GitHub Actions builds the app, runs unit tests and emulator tests on every push, and publishes the APK as the **latest** release. Locally: open in Android Studio, or `gradle :app:assembleDebug` with Gradle 8.13 and JDK 17.

The dot font is [Doto](https://fonts.google.com/specimen/Doto) (SIL Open Font License, included in `app/src/main/assets/fonts`).
