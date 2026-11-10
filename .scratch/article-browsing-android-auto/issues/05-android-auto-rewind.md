# Ticket 05 — Restore Android Auto’s 15-second rewind

Status: resolved
Type: task
Blocked by: None (can start immediately)

Part of [the feature spec](../spec.md), delivery step 5 and FR-005.

## What to build

Make **Back 15 seconds** available in Android Auto’s player on the tested host.
Use the same player command and saved progress as phone playback.

## Acceptance criteria

- [x] Confirm the missing button on the original build and the fix on DHU.
- [x] Publish the rewind button using the standard 15-second seek-back command.
- [x] Pass JVM tests, instrumentation source compilation, debug APK assembly,
  and Android lint.

## Answer

On November 10, 2026, the user reported that the DHU player showed only Play.
The phone’s media session advertised the standard rewind action, but Media3
1.8.0 did not publish the button in the platform playback state. Media3 1.9
added that compatibility for standard player commands. The app now uses the
1.9.1 patch release and retains its existing seek-back command and interval.
[Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3#1.9.0)

The user confirmed the fix after running the updated app from Android Studio on
the connected phone. The updated phone session exposed both the standard rewind
action and the **Back 15 seconds** button. All 185 JVM tests passed,
instrumentation sources compiled, the debug APK built, and lint passed.

Step 7 still tracks the full [rewind acceptance matrix](../../../ANDROID_AUTO_TESTING.md#ticket-05-rewind-acceptance),
including real-car coverage, playback state and speed, clamping at zero,
reconnect, saved progress, and phone activity closed. Connected instrumentation
execution and exact Android Auto and DHU versions remain unrecorded.
