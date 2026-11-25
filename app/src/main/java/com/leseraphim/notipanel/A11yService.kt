package com.leseraphim.notipanel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent

/**
 * 접근성 서비스
 * 1) 지금 화면에 떠 있는 앱이 홈(런처)인지 알려줌 → 홈이 아니면 아이콘을 옆으로 숨김
 * 2) 더블 터치 시 화면 끄기
 */
class A11yService : AccessibilityService() {

    companion object {
        var instance: A11yService? = null
            private set

        /** 현재 홈 화면인지 */
        var onHome = true
            private set

        /** 루트 예비 감지 결과 반영 (접근성이 꺼져 있을 때만) */
        var rootLastPkg = "-"
            private set

        fun reportFromRoot(pkg: String, homePkgs: Set<String>) {
            if (instance != null) return
            if (pkg == "com.leseraphim.notipanel" || pkg == "com.android.systemui") return
            rootLastPkg = pkg
            val home = pkg in homePkgs
            if (home != onHome) {
                onHome = home
                listeners.toList().forEach { it(home) }
            }
        }

        /** 설정 화면 표시용 */
        fun status(): String {
            val i = instance ?: return "접근성 꺼짐 → 루트로 감지 중\n앞 화면: $rootLastPkg  /  홈 판정: ${if (onHome) "예" else "아니오"}"
            return "앞 화면: ${i.lastPkg}  /  홈 판정: ${if (onHome) "예" else "아니오"}\n홈 앱: ${i.homePkgs.joinToString()}"
        }

        private val listeners = mutableSetOf<(Boolean) -> Unit>()

        /** 접근성 켜짐/꺼짐 알림 (터치 아이콘 창을 다시 붙이기 위해) */
        var onConnectionChanged: (() -> Unit)? = null
        fun addListener(l: (Boolean) -> Unit) { listeners += l }
        fun removeListener(l: (Boolean) -> Unit) { listeners -= l }

        /** 화면 끄기: 접근성 → 실패 시 루트 */
        fun lockScreen(): Boolean {
            if (instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true) return true
            return runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 223")).waitFor() == 0
            }.getOrDefault(false)
        }
    }

    private var homePkgs = setOf<String>()

    // 이 앱들이 앞에 와도 홈 상태를 바꾸지 않음 (알림창, 키보드, 우리 패널)
    private val ignore = setOf("com.android.systemui", "android")

    override fun onServiceConnected() {
        instance = this
        homePkgs = findHomePackages()
        onConnectionChanged?.invoke()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        onConnectionChanged?.invoke()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun findHomePackages(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.settings" }
            .toSet()
    }

    /** 설정 화면 표시용: 마지막으로 감지한 앞 화면 앱 */
    var lastPkg: String = "-"
        private set

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return

        // 1순위: 실제 화면에 떠 있는 맨 위 '앱' 창의 패키지
        // 2순위: 이벤트를 보낸 패키지
        val pkg = topAppPackage() ?: e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignore) return
        if (isIme(pkg)) return
        lastPkg = pkg
        if (homePkgs.isEmpty()) homePkgs = findHomePackages()
        val home = pkg in homePkgs
        if (home != onHome) {
            onHome = home
            listeners.toList().forEach { it(home) }
        }
    }

    private fun topAppPackage(): String? = runCatching {
        windows
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { w ->
                val root = w.root
                val p = root?.packageName?.toString()
                @Suppress("DEPRECATION") root?.recycle()
                p?.takeIf { it != packageName && it !in ignore }
            }
    }.getOrNull()

    private fun isIme(pkg: String): Boolean {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}
}
