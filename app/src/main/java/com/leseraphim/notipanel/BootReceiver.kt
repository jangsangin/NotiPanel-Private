package com.leseraphim.notipanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 재부팅 후 자동으로 터치 아이콘 다시 띄우기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 재부팅 후, 그리고 APK 업데이트 후에도 자동으로 다시 켜기
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.enabled(context) && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
