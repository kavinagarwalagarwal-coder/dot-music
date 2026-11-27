# Dot Phone

**A dialer made of dots: a Nothing-style phone app, where every contact's photo becomes a spinning record.**

Open the app and you're at the keypad: near-black glass, hairline edges, soft mint and blue auras, and the Doto dot type for labels. Four pill tabs — **Keypad**, **Recents**, **People**, **Stars** — keep everything one tap apart. Choose **Make Dot your phone app** and Dot places carrier calls itself, which is what gives it its own call screen. Until then, calls go to whichever phone app is the default.

[**⬇ Download the latest APK**](../../releases/tag/latest) · Android 8.0+

## The welcome tour

The first launch is a short, compulsory tour: what the app is, why it wants to be the phone app, the two permissions it uses (contacts and call history), and whether the dots should be white. There is no skip; the tour is the only way into the dialer. Settings → **Welcome tour** runs it again.

## A photo per contact

Open a contact's card and choose **Photo**: pick any image and Dot stores it on the phone, centre-cropped and downscaled. From then on that photo is the person's picture in lists, on their card, and — spinning — on the call screen. **Remove photo** goes back to the address-book photo, or to their initial in dot type.

Every picture is drawn the way the app has always drawn things: the image is sampled into a halftone grid where each dot's size follows brightness, and each dot is its own little spring. The record turns while the line is live, ripples roll out of the centre on a steady pulse, and the ring of dots around it works as an equaliser. Then the person's name rains in, column by column, in the dot font.

## The call screen

- **Answer or decline** with the two glass pills on an incoming call.
- **Mute**, **keypad**, **speaker** and **hold** are round glass buttons that light up white when they're on; **end call** sits underneath in red.
- **Hold** puts the line on hold and settles the record; the status chip turns amber.
- **Keypad** slides up an in-call dial pad that sends real DTMF tones, for phone menus and extensions.
- The timer runs under the name, and the row of dots beneath it lights one more dot for every minute of the call.

## Lists that stay quick

- **Recents** shows the call log with names, type and relative time; tap to call, long-press to drop the number into the keypad.
- **People** is a searchable address book — by name or by digits — with each row drawn as a dot avatar. Tap for the contact card, long-press to call.
- **Stars** is a grid of the people you keep close; star anyone from their card.

The contact card also has **Message** (opens your SMS app), **Copy** the number, and **Star/Unstar**.

## Updates

Settings → **Check for updates** downloads the newest build from this repository's GitHub release and hands it to Android's installer, so it replaces the app in place. Your contacts, custom photos and stars are untouched.

## Privacy

Everything stays on the phone. Contacts and call history are read only to show names, photos and recents, and custom photos live in the app's private storage. No account, no analytics, no network use except the update check. Settings → **Remove** deletes the photos you picked inside Dot.

## Build

GitHub Actions builds the app, runs unit tests and instrumented tests on every push, and publishes the APK as the **latest** release. Locally: open in Android Studio, or `gradle :app:assembleDebug` with Gradle 8.13 and JDK 17.

The dot font is [Doto](https://fonts.google.com/specimen/Doto) (SIL Open Font License, included in `app/src/main/assets/fonts`).
