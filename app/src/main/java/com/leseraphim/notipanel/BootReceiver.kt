package com.leseraphim.notipanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 재부팅 후 자동으로 터치 아이콘 다시 띄우기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.enabled(context) && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
