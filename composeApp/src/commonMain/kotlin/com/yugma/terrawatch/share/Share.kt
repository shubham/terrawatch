package com.yugma.terrawatch.share

/**
 * Task 11: "Share" from the quake detail sheet. [text] is already fully composed by the caller
 * (DetailSheet builds it from the quake + the same formatters used elsewhere on screen) - this
 * expect/actual only decides what "share" means on each platform, matching this codebase's
 * existing [com.yugma.terrawatch.motion.systemReducedMotion]/[com.yugma.terrawatch.map.QuakeMap]
 * pattern of one expect signature with a platform-appropriate actual per target:
 * - android: an `ACTION_SEND` chooser Intent.
 * - jvm (desktop): no share-sheet OS concept to hook into - copies [text] to the system clipboard
 *   instead, which is the closest desktop equivalent (see Share.jvm.kt).
 * - wasmJs: no-op for now (see Share.wasmJs.kt) - there is no live screen to call this from yet
 *   (web's data layer is Plan 3; App.kt renders a placeholder instead of Home).
 *
 * Android's actual needs a `android.content.Context` to launch an Intent, which this signature
 * deliberately does NOT carry - an `expect`/`actual` top-level function's signature must match
 * exactly across every target (unlike an `expect class` with no declared constructor, which is how
 * [com.yugma.terrawatch.location.LocationProvider] lets its Android actual take a Context-bearing
 * constructor its other actuals don't need). So Android's actual instead reads a small
 * process-lifetime holder, set once from `MainActivity.onCreate` - same "construct/wire the
 * platform-specific bit once at the entry point" spirit as [com.yugma.terrawatch.location.
 * LocationProvider]/`DriverFactory`, just via a holder rather than a constructor since the shared
 * declaration here is a function, not a class. See Share.android.kt for the holder itself.
 */
expect fun shareQuakeText(text: String)
