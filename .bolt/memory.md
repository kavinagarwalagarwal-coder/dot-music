# Project memory

## Product intent & audience

- Android music player app called **Dot Music**
- Features: full-screen player, background playback, notification listener (follows other music apps), live wallpaper, Quick Settings tile, 4 home-screen widgets, "For You" panel
- Part of a family of "Dot"-prefixed apps (a stale "Dot Dialer" manifest was previously mixed in)

## Brand personality & voice

- "Dot" naming convention across components and UI (DotPlayerActivity, DotUi, DotWallpaper, Dot Music)

## Visual design & UX direction

- UI components include `DotUi` (defined in `ForYouPanel.kt`), `ForYouPanel` for personalized content
- 4 home-screen widgets with broadcast receiver actions: `TOGGLE`, `NEXT`, `PREVIOUS`
- Quick Settings tile for playback control
- Live wallpaper component (audio visualizer permission)

## Decisions, constraints & rationale

- **Stack**: Kotlin, Android. AGP 8.13.2, Kotlin 2.3.10, compileSdk 36, Gradle 8.13 — all version-compatible
- **Build**: Release uses R8 minification/resource shrinking; ProGuard keep rules needed
- **Manifest rewrite**: `AndroidManifest.xml` was a stale Dot Dialer manifest declaring only `DialerActivity`. Rewrote to declare all Dot Music components: DotPlayerActivity, MainActivity, MusicListener (NotificationListenerService), PlaybackService (foreground media), DotWallpaper (live wallpaper), Quick Settings tile, WidgetActionReceiver, 4 widgets. Added permissions: internet, notification listener access, foreground media playback, audio visualizer
- **Environment constraint**: No Java/Android SDK or Gradle in dev environment; builds cannot be run locally, fixes are made by code inspection
- Tests launch player activity and inflate widgets; old manifest didn't expose these, causing failures

## Open questions & assumptions

- CI failure root cause inferred from source review, not confirmed from actual CI logs — user was asked to paste the CI error if the fix doesn't resolve it
- Assumed manifest was the sole failure point; other R8/keep-rule issues may surface