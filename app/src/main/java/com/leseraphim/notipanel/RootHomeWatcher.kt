package com.leseraphim.notipanel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * 접근성이 꺼져 있을 때의 예비 홈 감지 (루트).
 * su 셸 하나를 계속 열어두고 0.8초마다 맨 앞 앱을 확인한다.
 */
class RootHomeWatcher(private val ctx: Context, private val onPkg: (String) -> Unit) {

    @Volatile private var running = false
    /** 루트 상태: null=확인 중, true=동작, false=거부됨 */
    @Volatile var rootOk: Boolean? = null
        private set
    var onState: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    val homePkgs: Set<String> by lazy {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.settings" }
            .toSet()
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "root-home-watcher", isDaemon = true) { loop() }
    }

    fun stop() { running = false }

    private fun fail() {
        rootOk = false
        main.post { onState?.invoke() }
    }

    private fun loop() {
        val p = runCatching { ProcessBuilder("su").redirectErrorStream(true).start() }.getOrNull()
        if (p == null) { running = false; fail(); return }
        val w = OutputStreamWriter(p.outputStream)
        val r = BufferedReader(InputStreamReader(p.inputStream))
        val pm = ctx.getSystemService(PowerManager::class.java)
        val re = Regex("""u\d+ ([\w.]+)/""")
        try {
            while (running) {
                if (pm.isInteractive) {
                    w.write("dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'; echo __END__\n")
                    w.flush()
                    var line: String?
                    var pkg: String? = null
                    while (r.readLine().also { line = it } != null) {
                        if (line == "__END__") break
                        if (pkg == null) pkg = re.find(line!!)?.groupValues?.get(1)
                    }
                    if (line == null) { fail(); break }   // su 거부/종료
                    if (rootOk != true) { rootOk = true; main.post { onState?.invoke() } }
                    pkg?.let { main.post { onPkg(it) } }
                }
                Thread.sleep(800)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { w.write("exit\n"); w.flush() }
            p.destroy()
            running = false
        }
    }
}
