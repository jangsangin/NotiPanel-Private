package com.leseraphim.notipanel

import android.content.Context
import android.provider.Settings

/**
 * 패널이 열려 있는 동안 상단 배너(헤드업 알림) 끄기.
 * 시스템 설정 'heads_up_notifications_enabled'를 잠깐 0으로 → 지금 떠 있는 배너도 바로 사라짐.
 * 패널이 닫히면 원래 값으로 되돌림. (보안 설정 쓰기 권한 필요, 없으면 루트로 시도)
 */
object HeadsUp {
    private const val KEY = "heads_up_notifications_enabled"
    private const val PREF_SAVED = "hun_saved"     // 우리가 끈 상태인지 + 원래 값

    fun suppress(c: Context) {
        val cr = c.contentResolver
        val cur = runCatching { Settings.Global.getInt(cr, KEY, 1) }.getOrDefault(1)
        if (cur == 0) return                        // 원래 꺼져 있으면 건드리지 않음
        Prefs.get(c).edit().putInt(PREF_SAVED, cur).apply()
        if (!write(c, 0)) Prefs.get(c).edit().remove(PREF_SAVED).apply()
    }

    fun restore(c: Context) {
        val p = Prefs.get(c)
        if (!p.contains(PREF_SAVED)) return
        val v = p.getInt(PREF_SAVED, 1)
        write(c, v)
        p.edit().remove(PREF_SAVED).apply()
    }

    private fun write(c: Context, v: Int): Boolean {
        if (PermissionFixer.hasSecureSettings(c)) {
            if (runCatching { Settings.Global.putInt(c.contentResolver, KEY, v) }.getOrDefault(false)) return true
        }
        // 권한이 없으면 루트로 (루트 없으면 아무 일 안 함)
        RootShell.run("settings put global $KEY $v")
        return true
    }
}
