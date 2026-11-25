package com.leseraphim.notipanel

import java.io.OutputStreamWriter
import java.util.concurrent.Executors

/** su 셸 하나를 계속 열어두고 명령을 바로 보내는 도우미 (매번 su 새로 띄우는 지연 제거) */
object RootShell {
    private val exec = Executors.newSingleThreadExecutor()
    private var proc: Process? = null
    private var out: OutputStreamWriter? = null

    private fun ensure(): Boolean {
        val p = proc
        if (p != null && runCatching { p.exitValue() }.isFailure) return true   // 아직 살아있음
        return runCatching {
            val np = ProcessBuilder("su").redirectErrorStream(true).start()
            // 출력은 버림
            Thread { runCatching { np.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } }
                .apply { isDaemon = true }.start()
            proc = np
            out = OutputStreamWriter(np.outputStream)
            true
        }.getOrDefault(false)
    }

    /** 명령 보내고, 끝나면 done 호출 */
    fun run(cmd: String, done: () -> Unit = {}) {
        exec.execute {
            if (ensure()) {
                runCatching {
                    out!!.write("$cmd\n")
                    out!!.flush()
                }.onFailure { proc = null }
            }
            done()
        }
    }

    /** 미리 띄워두기 */
    fun warmUp() = exec.execute { ensure() }
}
