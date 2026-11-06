# Dot Music

**A full-screen dot player for YouTube Music, with recommendations that learn on your phone.**

Open the app and you're in the player: the album cover becomes a spinning record made of hundreds of dots, in a dot-matrix style inspired by Nothing. Another app does the playing: YouTube Music by default, or any player you choose (Dot Player, Spotify, JioSaavn…). Dot is the screen, the controls and the picks.

[**⬇ Download the latest APK**](../../releases/tag/latest) · Android 8.0+ · Needs [YouTube Music](https://play.google.com/store/apps/details?id=com.google.android.apps.youtube.music) · No internet permission

## Choosing the player app

Settings → **Player app** lists the music players on your phone. Dot follows and controls the one you pick through Android's standard media controls, and sends its picks there. **Check what the player app supports** shows exactly what that app allows (song info, cover, seek, queue, and whether it can start songs while you stay in Dot), with a **Send a test song** button. Apps that can't take songs in the background are opened instead; apps that take no requests at all can still be shown, controlled and learned from.

## Playing through YouTube Music

- **For you** (top right) opens search and your recommendation shelves. Type anything and press search, or tap a pick: it starts on YouTube Music, which keeps a radio of similar songs going after it.
- Dot hands songs straight to YouTube Music's media session, the way Assistant and Android Auto do, so you stay in the player. If YouTube Music isn't running yet, it opens and starts playing.
- The bar at the bottom shows **Dot's next pick**; tap it to play it now. Turn on **Dot picks the next song** in settings and Next (or a left flick of the record) plays Dot's pick instead of YouTube Music's queue.
- YouTube Music's own queue shows up under For you too, when it shares it.

## Recommendations

Dot learns only from what you play, and keeps it in the app's private storage on your phone.

- **How a song ended.** Played through counts as a like, half-way as a shrug, skipped early as a dislike. Songs you skip again and again stop coming up.
- **Recency.** Every signal fades over about a month, so your taste can move on.
- **Flow.** Which song you happily played after which (song to song, and artist to artist), followed two steps out from what's playing now. This drives **Up next**.
- **Time of day.** Songs you lean on in the morning versus late at night: **Your evening**, **Your late night**…
- **Rest.** Songs you just heard sit out for a while.
- **Exploring.** Each pick is a Thompson sample of how much you like the song, so ones Dot is unsure about still get a turn. A diversity pass spreads artists out. **Explore** suggests artists that follow your favourites, and radios seeded from songs you love, where YouTube Music brings in music you haven't heard.

Shelves: **Up next**, **Your ‹time of day›**, **On repeat**, **Rediscover** (old favourites you drifted from) and **Explore**. You can switch learning off or forget everything in settings.

## Widgets

| Size | What's on it |
| --- | --- |
| **2×2 record** | The cover as a dot record, a comet of dots orbiting it, rings of dots rippling out of the play button. |
| **4×2 player** | Dot record with its comet, the title set in dots, a live dot equaliser, a dot progress bar, the clock, previous / play / next. |
| **4×1 strip** | Live dot equaliser, title in dots, play and next. |
| **4×4 stage** | The player screen as a widget: the dot record inside its equaliser ring, drawn by the player's own code, with the comet orbiting and ripples running through it, plus title, progress and controls. |

The equaliser, the orbiting comet and the ripples are animated vector drawables that your launcher plays by itself, so they stay smooth and cost almost no battery. They run only while music plays. The progress bar steps forward every few seconds, and only while the screen is on.

## Live wallpaper

The full player, alive on your home screen: the record spins and ripples with the music, the ring equaliser moves, and the dots move away from your finger as you swipe between pages. Tap the record to play or pause. It only draws while visible, and drops to about 20 fps when the music is paused. Set it from the app with **Set as live wallpaper**.

It sits on top of your own wallpaper photo (Android doesn't let apps read your current wallpaper, so you pick the photo once in the app). The dots can show always or **only while music plays**, fading in when a song starts and away when it stops, and a **Quick Settings tile** switches them on and off from the notification shade. With the dots hidden it's just your photo and draws nothing more.

## The full-screen player

It's what opens when you start the app, or tap a widget's record or title.

- **A record of ~700 springy dots** that slowly spins while the song plays and settles when you pause.
- **Ripples on the beat** roll out through the record, and a **ring of dots** around it works as a circular equaliser.
- **React to the real beat (optional).** The player follows the actual music using Android's audio visualizer. Android files this under the microphone permission, but it only measures loudness per frequency and never records anything. When it's off, a steady pulse plays instead.
- **Play with it.** Drag your finger through the dots to push them aside. Tap the record to pause, or flick it left or right to skip: the record bursts apart and the dots reassemble into the next cover.
- **Title in dots** that rains in column by column and scrolls when it's long. Drag the dot progress bar to seek.
- Colour dots from the cover, or **white dots** in Nothing style. Respects Android's "remove animations" setting.

## Setup

1. Install YouTube Music and sign in.
2. Install the APK from [Releases](../../releases/tag/latest) and open Dot Music.
3. Tap **Connect YouTube Music** at the bottom and allow notification access. This is how Android lets an app see and control what's playing.
4. Open **For you** and play something. Widgets and the live wallpaper are in settings (**•••**).

The widgets, the wallpaper and the player also follow other music apps (Spotify, Apple Music and others) unless you turn on **Only follow YouTube Music**.

## Privacy

No internet permission, no accounts, no analytics. The song, cover and play state are read from YouTube Music's media session. Your listening history (song, artist, how long you listened, the hour) is kept in the app's private storage to make recommendations, and never leaves the phone. Settings has **Forget my listening history**.

## Build

GitHub Actions builds the app, runs unit tests and emulator tests on every push, and publishes the APK as the **latest** release. Locally: open in Android Studio, or `gradle :app:assembleDebug` with Gradle 8.13 and JDK 17.

The dot font is [Doto](https://fonts.google.com/specimen/Doto) (SIL Open Font License, included in `app/src/main/assets/fonts`).
