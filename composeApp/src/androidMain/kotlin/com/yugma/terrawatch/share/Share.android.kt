package com.yugma.terrawatch.share

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

// Round 2 (stale-kdoc fix): set once from ensureKoinStarted (di/KoinBootstrap.kt) -- called from
// BOTH of this app's entry points, MainActivity.onCreate AND AlertDigestWorker.doWork() (a
// headless WorkManager process start, MainActivity never created -- see ensureKoinStarted's own
// kdoc for why a worker-only process needs this bootstrap too), inside that function's own
// idempotent guard. (Was: "set once from MainActivity.onCreate's own first-launch guard" -- stale
// since the C1 fix moved this call INSIDE ensureKoinStarted so a headless worker-started process
// gets it too; see that function's own kdoc for the crash this closed.) shareQuakeText's
// expect/actual signature is shared verbatim across all three platforms (see Share.kt's kdoc), so
// Android can't take Context as a parameter the way an `expect class` constructor could; this
// module-level holder is the substitute. applicationContext (not the Activity) is stored
// specifically so this can never leak an Activity past its lifecycle.
private lateinit var appContext: Context

/** Must run before the first [shareQuakeText] call ever reaches this actual - i.e. before the
 * Share button can be tapped, since MainActivity.onCreate calls this ahead of `setContent {}`. */
fun initShareContext(context: Context) {
    appContext = context.applicationContext
}

/**
 * `ACTION_SEND` plain-text chooser. `FLAG_ACTIVITY_NEW_TASK` is required because [appContext] is an
 * Application (not Activity) context - starting an Activity from one always needs that flag.
 * `Intent.createChooser` (rather than sending [sendIntent] directly) guarantees a picker shows even
 * when exactly one app can handle it, and never remembers a default - appropriate for a
 * share-to-anywhere action the user will want a fresh choice for each time.
 */
actual fun shareQuakeText(text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(sendIntent, null).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(chooser)
}

/**
 * Plan 4 Task 4b: exactly the brief's own specified check. Requires [packageName] to be declared
 * in this app's manifest `<queries>` element on API 30+ (Android's package-visibility filtering) -
 * without it, this returns `false` for every app regardless of whether it's actually installed,
 * indistinguishable from a genuinely-absent app. See `AndroidManifest.xml`'s own `<queries>` entry.
 */
actual fun isPackageInstalled(packageName: String): Boolean =
    appContext.packageManager.getLaunchIntentForPackage(packageName) != null

/**
 * Package-targeted `ACTION_SEND` - `setPackage(packageName)` is the one difference from
 * [shareQuakeText]'s chooser Intent: no `Intent.createChooser` wrapper (the target is already
 * explicit, there is nothing left to choose between), so a tap always opens directly into
 * [packageName]'s own compose/share UI.
 */
actual fun sharePackaged(packageName: String, text: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        setPackage(packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(sendIntent)
}

/** `ACTION_VIEW` - opens [url] in whatever app/browser the OS resolves it to (a news headline tap
 * has no single fixed target the way [sharePackaged] does, so this stays a plain implicit intent,
 * no `setPackage`). */
actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    appContext.startActivity(intent)
}

/**
 * feat/feed-visit-ux, "real share app icons": see [appIcon]'s own common kdoc for the null
 * contract. `getApplicationIcon` throws [PackageManager.NameNotFoundException] for a package that
 * isn't installed - the ONE expected failure mode, given every real call site already checked
 * [isPackageInstalled] first - caught by name rather than a bare `catch (t: Throwable)` so this
 * doesn't also silently swallow a genuine programming error. A broader `catch (e: Exception)`
 * around the conversion itself (not just the lookup) is still warranted: [drawableToImageBitmap]
 * allocates a real [Bitmap] sized to the drawable's own reported intrinsic dimensions, which is
 * outside this function's control and could in principle throw (e.g. [OutOfMemoryError] is an
 * `Error`, not caught here deliberately - a genuine OOM should propagate, not be silently
 * swallowed into "just show the monogram").
 */
actual fun appIcon(packageName: String): ImageBitmap? = try {
    drawableToImageBitmap(appContext.packageManager.getApplicationIcon(packageName))
} catch (e: PackageManager.NameNotFoundException) {
    null
} catch (e: IllegalArgumentException) {
    // Bitmap.createBitmap's own documented throw for a non-positive width/height - a defensive
    // catch, not an expected path: every real launcher icon reports a positive intrinsic size, but
    // a hand-rolled conversion has no platform guarantee of that the way an OS-drawn icon view
    // would, so this degrades to the monogram fallback rather than crashing on a shape this
    // function didn't anticipate.
    null
}

/**
 * Drawable -> [ImageBitmap], by hand: [Bitmap.createBitmap] + [Canvas.draw] - no new dependency,
 * same "this codebase converts/draws its own graphics primitives rather than reaching for a
 * library" posture [appIcon]'s own common kdoc points at. `coerceAtLeast(1)` guards the one real
 * edge case a launcher icon's [Drawable.getIntrinsicWidth]/[Drawable.getIntrinsicHeight] can
 * report (-1, "no intrinsic size") - vanishingly rare for an actual `ApplicationInfo` icon
 * (density-bucketed PNGs/adaptive-icon XML always report a real size), but a 1x1 fallback bitmap
 * that renders as an invisible dot is a safer failure than an [IllegalArgumentException] crash
 * from handing [Bitmap.createBitmap] a negative dimension.
 */
private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
