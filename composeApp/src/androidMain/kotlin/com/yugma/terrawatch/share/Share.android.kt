package com.yugma.terrawatch.share

import android.content.Context
import android.content.Intent

// Set once from MainActivity.onCreate's existing "first launch this process" guard (the same
// block that builds the other process-lifetime singletons - QuakeDao, HttpClient, LocationProvider
// - see MainActivity.kt) - shareQuakeText's expect/actual signature is shared verbatim across all
// three platforms (see Share.kt's kdoc), so Android can't take Context as a parameter the way an
// `expect class` constructor could; this module-level holder is the substitute. applicationContext
// (not the Activity) is stored specifically so this can never leak an Activity past its lifecycle.
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
