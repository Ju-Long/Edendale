package com.babasama.edendale.android.xr

import android.content.Context
import android.content.Intent

/** The Android side of the theater's entry: where it runs and how it is started. */
object XrTheater {

    /** True on an Android XR device that has the `xr` split installed. */
    fun isAvailable(context: Context): Boolean = XrTheaterRules.offers(
        hasSpatialFeature = context.packageManager.hasSystemFeature(XrTheaterRules.FEATURE_SPATIAL),
        installedSplits = context.applicationInfo.splitNames?.toList(),
        hasVideo = true,
    )

    /** The intent the player sends; the activity lives in the split, so it is named, not referenced. */
    fun intent(context: Context, handover: XrTheaterHandover): Intent =
        Intent().setClassName(context.packageName, XrTheaterRules.ACTIVITY_CLASS).apply {
            action = Intent.ACTION_MAIN
            handover.write(IntentExtras(this))
        }

    /** The handover an intent carries, or null for anything else. */
    fun handover(intent: Intent?): XrTheaterHandover? = intent?.let { XrTheaterHandover.read(IntentExtras(it)) }
}

private class IntentExtras(private val intent: Intent) : ExtrasReader, ExtrasWriter {
    override fun string(key: String): String? = intent.getStringExtra(key)
    override fun int(key: String): Int? = if (intent.hasExtra(key)) intent.getIntExtra(key, 0) else null
    override fun long(key: String): Long? = if (intent.hasExtra(key)) intent.getLongExtra(key, 0L) else null
    override fun boolean(key: String): Boolean? = if (intent.hasExtra(key)) intent.getBooleanExtra(key, false) else null
    override fun float(key: String): Float? = if (intent.hasExtra(key)) intent.getFloatExtra(key, 0f) else null

    override fun put(key: String, value: String) { intent.putExtra(key, value) }
    override fun put(key: String, value: Int) { intent.putExtra(key, value) }
    override fun put(key: String, value: Long) { intent.putExtra(key, value) }
    override fun put(key: String, value: Boolean) { intent.putExtra(key, value) }
    override fun put(key: String, value: Float) { intent.putExtra(key, value) }
}
