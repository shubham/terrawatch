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

/**
 * Plan 4 Task 4b: is [packageName] installed (and visible to this app) right now? Backs
 * `com.yugma.terrawatch.detail.visibleShareTargets`'s real call site - [DetailSheet][com.yugma.
 * terrawatch.detail.DetailSheet] hides a quick-share button entirely rather than graying it out,
 * so this only ever needs a yes/no answer, never a reason. Android's actual:
 * `PackageManager.getLaunchIntentForPackage(packageName) != null` (the brief's own specified
 * check) - requires the target package to be declared in a manifest `<queries>` element on API
 * 30+ (package-visibility filtering), see `AndroidManifest.xml`'s own comment for the 3 entries
 * this feature needs. jvm/wasmJs: always `false` - neither platform has an installed-mobile-app
 * concept, so every quick-share button simply never appears there (same "Android-only scope"
 * reasoning [shareQuakeText]'s own kdoc documents per-target).
 */
expect fun isPackageInstalled(packageName: String): Boolean

/**
 * Plan 4 Task 4b: package-targeted `ACTION_SEND` - the quick-share row's tap action, distinct from
 * [shareQuakeText]'s generic chooser (`Intent.createChooser`, no `setPackage`). [text] is the SAME
 * string [DetailSheet][com.yugma.terrawatch.detail.DetailSheet] already built via
 * [com.yugma.terrawatch.detail.buildShareText] for the chooser button - this function only differs
 * in WHICH app receives it, not what it says.
 */
expect fun sharePackaged(packageName: String, text: String)

/**
 * Plan 4 Task 5: opens [url] in the platform's own browser/handler (`ACTION_VIEW` on Android) -
 * backs DetailSheet's "In the news" headline taps. Same holder-based Android actual as
 * [shareQuakeText]/[sharePackaged] (no Context in this shared signature); jvm best-efforts a real
 * browser launch (`java.awt.Desktop`, this project's own "compile-only for CI" jvm target per the
 * plan's Architecture line, not a real runtime surface); wasmJs is a no-op, same "no live screen
 * reaches this yet" reasoning [shareQuakeText]'s own wasmJs actual documents.
 */
expect fun openUrl(url: String)
