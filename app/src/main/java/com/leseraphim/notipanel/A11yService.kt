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

        private val listeners = mutableSetOf<(Boolean) -> Unit>()
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

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignore) return
        if (isIme(pkg)) return
        val home = pkg in homePkgs
        if (home != onHome) {
            onHome = home
            listeners.toList().forEach { it(home) }
        }
    }

    private fun isIme(pkg: String): Boolean {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}
}
