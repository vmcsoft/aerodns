package com.vmcsoft.aerodns.presentation.tile

import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi

/**
 * Quick Settings [Tile.subtitle] requires API 29+. The app minSdk is 24.
 */
object QuickSettingsTileCompat {
    const val SUBTITLE_MIN_SDK = Build.VERSION_CODES.Q

    fun supportsSubtitle(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= SUBTITLE_MIN_SDK

    fun setSubtitle(tile: Tile, subtitle: CharSequence?, sdkInt: Int = Build.VERSION.SDK_INT) {
        if (supportsSubtitle(sdkInt) && Build.VERSION.SDK_INT >= SUBTITLE_MIN_SDK) {
            Api29.setSubtitle(tile, subtitle)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {
        fun setSubtitle(tile: Tile, subtitle: CharSequence?) {
            tile.subtitle = subtitle
        }
    }
}
