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
            val changedPkg = pkg != rootLastPkg
            rootLastPkg = pkg
            if (changedPkg) pkgListener?.invoke()
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

        /** 상단 알림창/빠른 설정이 내려와 있는지 */
        var shadeOpen = false
            private set

        /** 앞 화면 앱이 바뀔 때 (상태 알림 갱신용) */
        var pkgListener: (() -> Unit)? = null

        fun currentPkg(): String = instance?.lastPkg ?: rootLastPkg

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
        // 상단 알림창이 내려왔는지 먼저 확인 (알림창은 시스템UI라 아래에서 걸러지므로)
        val shade = isShadeOpen()
        if (shade != shadeOpen) {
            shadeOpen = shade
            listeners.toList().forEach { it(onHome) }
        }

        val pkg = topAppPackage() ?: e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignore) return
        if (isIme(pkg)) return
        val changedPkg = pkg != lastPkg
        lastPkg = pkg
        if (changedPkg) pkgListener?.invoke()
        if (homePkgs.isEmpty()) homePkgs = findHomePackages()
        // 홈 제스처를 시작하는 순간(앱 뒤로 런처 창이 보이기 시작) 바로 홈으로 판단
        val home = pkg in homePkgs || launcherVisible()
        if (home != onHome) {
            onHome = home
            listeners.toList().forEach { it(home) }
        }
    }

    /** 시스템UI 창이 화면 절반 이상을 덮고 있으면 알림창/빠른 설정이 내려온 것 */
    private fun isShadeOpen(): Boolean = runCatching {
        val h = resources.displayMetrics.heightPixels
        val r = android.graphics.Rect()
        windows.any { w ->
            if (w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) return@any false
            w.getBoundsInScreen(r)
            if (r.height() < h * 0.5f) return@any false
            w.root?.packageName?.toString() == "com.android.systemui"
        }
    }.getOrDefault(false)

    /** 런처 창이 화면에 보이는지 (앱 안에 있을 땐 런처 창이 목록에 안 나옴) */
    private fun launcherVisible(): Boolean = runCatching {
        windows.any { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                (w.root?.packageName?.toString() in homePkgs)
        }
    }.getOrDefault(false)

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

    /** 지금 떠 있는 키보드의 실제 윗선(화면 좌표). 못 찾거나 이상한 값이면 null */
    fun imeTop(): Int? = runCatching {
        val h = resources.displayMetrics.heightPixels
        val w = windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return@runCatching null
        val r = android.graphics.Rect()
        w.getBoundsInScreen(r)
        // 키보드 창이 화면 전체 크기인 경우가 있어서, 실제 키보드 뷰 위치도 확인
        val rr = android.graphics.Rect()
        w.root?.getBoundsInScreen(rr)
        val top = maxOf(r.top, rr.top)
        // 말이 되는 범위(화면 30%~92%)만 믿음
        if (r.height() > 0 && top in (h * 0.30f).toInt()..(h * 0.92f).toInt()) top else null
    }.getOrNull()

    /** 터치를 밑에 있는 앱에 다시 넣기 (탭 또는 짧은 스와이프) */
    fun replay(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, done: () -> Unit): Boolean {
        val path = android.graphics.Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path, 0, durationMs.coerceIn(40, 1500)
        )
        val g = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: android.accessibilityservice.GestureDescription?) = done()
            override fun onCancelled(d: android.accessibilityservice.GestureDescription?) = done()
        }, null)
    }

    // 캡처 처리는 백그라운드에서 (화면 끊김 방지)
    private val shotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 화면 전체 캡처 (패널 배경용). 실패하면 한 번 더 시도 후 null */
    fun captureScreen(cb: (android.graphics.Bitmap?) -> Unit, retry: Boolean = true) {
        if (android.os.Build.VERSION.SDK_INT < 30) { cb(null); return }
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val bmp = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        r.hardwareBuffer.close()
                        cb(bmp)
                    }
                    override fun onFailure(errorCode: Int) {
                        // 너무 자주 찍으면 실패 → 잠깐 뒤 한 번 더
                        if (retry) mainHandler.postDelayed({ captureScreen(cb, false) }, 350) else cb(null)
                    }
                })
        }.onFailure { cb(null) }
    }

    /** 화면 캡처 → 원하는 영역 평균 밝기(0~1). 실패하면 null */
    fun sampleLuminance(area: android.graphics.Rect, cb: (Float?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 30) { cb(null); return }
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, shotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle(); r.hardwareBuffer.close()
                        if (bmp == null) { mainHandler.post { cb(null) }; return }
                        var sum = 0f; var n = 0
                        val step = 6
                        val x0 = area.left.coerceIn(0, bmp.width - 1)
                        val x1 = area.right.coerceIn(0, bmp.width - 1)
                        val y0 = area.top.coerceIn(0, bmp.height - 1)
                        val y1 = area.bottom.coerceIn(0, bmp.height - 1)
                        var y = y0
                        while (y <= y1) {
                            var x = x0
                            while (x <= x1) {
                                val c = bmp.getPixel(x, y)
                                sum += (0.2126f * android.graphics.Color.red(c) +
                                        0.7152f * android.graphics.Color.green(c) +
                                        0.0722f * android.graphics.Color.blue(c)) / 255f
                                n++
                                x += step
                            }
                            y += step
                        }
                        bmp.recycle()
                        val res = if (n > 0) sum / n else null
                        mainHandler.post { cb(res) }
                    }
                    override fun onFailure(errorCode: Int) { mainHandler.post { cb(null) } }
                })
        }.onFailure { cb(null) }
    }
}
