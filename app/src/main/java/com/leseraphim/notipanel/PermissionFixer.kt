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
            echo __DONE__
        """.trimIndent()
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
