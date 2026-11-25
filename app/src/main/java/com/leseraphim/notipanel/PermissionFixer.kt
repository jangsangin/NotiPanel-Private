package com.leseraphim.notipanel

import android.content.Context
import kotlin.concurrent.thread

/**
 * 재부팅 등으로 풀린 권한을 루트로 다시 켜기.
 * 접근성, 알림 접근, 다른 앱 위에 표시, 알림 표시, 배터리 최적화 제외, 백그라운드 실행 허용.
 */
object PermissionFixer {

    fun script(c: Context): String {
        val pkg = c.packageName
        val a11y = "$pkg/$pkg.A11yService"
        val listener = "$pkg/$pkg.NotiListenerService"
        return """
            cur=${'$'}(settings get secure enabled_accessibility_services)
            case "${'$'}cur" in
              *"$a11y"*) ;;
              ""|null) settings put secure enabled_accessibility_services "$a11y" ;;
              *) settings put secure enabled_accessibility_services "${'$'}cur:$a11y" ;;
            esac
            settings put secure accessibility_enabled 1
            cmd notification allow_listener $listener
            appops set $pkg SYSTEM_ALERT_WINDOW allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
            dumpsys deviceidle whitelist +$pkg
            cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow
            cmd appops set $pkg RUN_IN_BACKGROUND allow
            pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
            echo __DONE__
        """.trimIndent()
    }

    fun hasSecureSettings(c: Context) =
        c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 루트 없이 복구 (보안 설정 쓰기 권한이 있을 때): 접근성 다시 켜기 */
    fun restoreNoRoot(c: Context): Boolean {
        if (!hasSecureSettings(c)) return false
        return runCatching {
            val cr = c.contentResolver
            val comp = "${c.packageName}/${c.packageName}.A11yService"
            val cur = android.provider.Settings.Secure.getString(
                cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!cur.split(':').any { it.equals(comp, true) || it.equals("${c.packageName}/.A11yService", true) }) {
                val next = if (cur.isBlank()) comp else "$cur:$comp"
                android.provider.Settings.Secure.putString(
                    cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next
                )
            }
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        }.getOrDefault(false)
    }

    /** 이 폰에서 루트가 실제로 되는지 (2초 안에 확인) */
    fun checkRoot(done: (Boolean) -> Unit) {
        thread(name = "root-check") {
            val ok = runCatching {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                val finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) { p.destroy(); false }
                else p.inputStream.bufferedReader().readText().contains("uid=0")
            }.getOrDefault(false)
            done(ok)
        }
    }

    /** 루트로 실행하고 끝나면 done(성공 여부) — 백그라운드 스레드에서 실행됨 */
    fun restore(c: Context, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        thread(name = "perm-fix") {
            val ok = runCatching {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                p.outputStream.bufferedWriter().use { it.write(script(app) + "\nexit\n") }
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                out.contains("__DONE__")
            }.getOrDefault(false)
            done(ok)
        }
    }
}
