package com.leseraphim.notipanel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast

/**
 * 다른 앱이 켜져 있을 때만 화면 오른쪽 끝에 얇은 사이드바를 띄우고,
 * 사이드바를 왼쪽으로 밀면 알림 패널을 연다. 홈 화면에서는 아무것도 띄우지 않는다.
 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL = "status_nobadge2"
        private const val NOTI_ID = 1

        /** 패널이 마지막으로 닫힌 이유 (상태 알림에 표시) */
        var lastCloseReason = "-"

        /** 설정의 '테스트로 보이기' 버튼용 */
        var instance: OverlayService? = null
            private set

        fun start(c: Context) {
            c.startForegroundService(Intent(c, OverlayService::class.java))
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var barWm: WindowManager? = null      // 사이드바를 붙인 WindowManager
    private var barRoot: FrameLayout? = null      // 터치 영역(투명, 넓음)
    private var barView: View? = null             // 눈에 보이는 회색 바
    private var barLp: WindowManager.LayoutParams? = null
    private var isButton = false
    private var isLeft = false
    private var shown = false
    private var forceShow = false
    private var panel: PanelDialog? = null
    private var rootWatcher: RootHomeWatcher? = null
    private val handler = Handler(Looper.getMainLooper())

    private val homeListener: (Boolean) -> Unit = { updateVisibility() }

    // ───────── 잠금화면 ─────────
    private var locked = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 화면 꺼지면 패널은 애니메이션 없이 즉시 닫고, 사이드바도 즉시 숨김
                    locked = true
                    panel?.dismissNow()
                    panel = null
                    updateVisibility(animate = false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    locked = isKeyguardLocked()
                    updateVisibility(animate = false)
                }
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    // 홈 키·홈 제스처·최근 앱: 패널 닫고 홈 여부 다시 확인
                    val reason = i.getStringExtra("reason")
                    if (reason == "homekey" || reason == "recentapps" || reason == "gestureNav") {
                        if (panel != null) { lastCloseReason = "홈/최근 앱 키" }
                        panel?.dismissNow(); panel = null
                        handler.postDelayed({ checkHomeNow() }, 250)
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 잠금 해제: 패널은 닫힌 상태 유지, 사이드바만 상황에 맞게
                    locked = false
                    updateVisibility()
                }
            }
        }
    }

    private fun isKeyguardLocked() =
        getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_OPACITY -> barView?.alpha = barAlpha()
            // 위치·크기 바꾸면 바로 다시 그리고, 홈 화면에서도 3초간 보여줘서 확인 가능
            Prefs.KEY_BAR_POS, Prefs.KEY_BAR_LEN, Prefs.KEY_BAR_W,
            Prefs.KEY_SIDE_LEFT, Prefs.KEY_BUTTON_MODE -> {
                rebuildBar()
                previewBar()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        startAsForeground()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        locked = isKeyguardLocked()
        registerReceiver(screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            @Suppress("DEPRECATION") addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }, if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_EXPORTED else 0)
        A11yService.addListener(homeListener)
        A11yService.onConnectionChanged = { rebuildBar(); updateWatcher() }
        A11yService.pkgListener = { refreshStatus() }
        updateWatcher()
        rebuildBar()
        handler.postDelayed(sampler, 800)
        RootShell.warmUp()
        BadgeTool.disable(this)   // 런처가 채널 설정을 무시하고 붙이는 아이콘 뱃지 끄기 (루트)
        HeadsUp.restore(this)
        if (A11yService.instance == null) {
            if (!PermissionFixer.restoreNoRoot(this)) PermissionFixer.restore(this)
        }
        if (A11yService.instance == null) {
            Toast.makeText(this, "접근성을 켜야 사이드바가 키보드 위에 보여요", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        A11yService.removeListener(homeListener)
        runCatching { unregisterReceiver(screenReceiver) }
        A11yService.onConnectionChanged = null
        A11yService.pkgListener = null
        rootWatcher?.stop()
        handler.removeCallbacksAndMessages(null)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panel?.dismissNow()
        HeadsUp.restore(this)
        removeBar()
        super.onDestroy()
    }

    // ───────── 상주 알림 ─────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        // 예전 채널(아이콘 뱃지 뜨던 것) 정리
        listOf("overlay", "status", "status_nobadge").forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "사이드바 상태", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)          // 앱 아이콘에 뱃지 안 붙게
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = buildStatus()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    /** 상단 알림에 지금 감지 상태 표시 */
    private fun buildStatus(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val method = when {
            A11yService.instance != null -> "접근성 ✓"
            rootWatcher?.rootOk == true -> "루트 ✓ (키보드 위엔 안 보임)"
            rootWatcher?.rootOk == false -> "✗ 감지 불가(항상 표시 중) — 접근성을 켜 주세요"
            else -> "루트 확인 중…"
        }
        val where = if (A11yService.onHome) "홈" else "다른 앱"
        val bar = if (shown) "보임" else "숨김"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_touch)
            .setContentTitle("노티패널 · 사이드바 $bar")
            .setContentText("감지: $method")
            .setStyle(Notification.BigTextStyle().bigText(
                "감지: $method\n지금 화면: $where (${A11yService.currentPkg()})\n사이드바: $bar\n패널 마지막 닫힘: $lastCloseReason"
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setBadgeIconType(Notification.BADGE_ICON_NONE)
            .setNumber(0)
            .build()
    }

    private fun refreshStatus() {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildStatus())
    }

    // ───────── 홈 감지 (접근성 없으면 루트) ─────────
    private fun updateWatcher() {
        if (A11yService.instance == null) {
            if (rootWatcher == null) {
                rootWatcher = RootHomeWatcher(this) { pkg ->
                    A11yService.reportFromRoot(pkg, rootWatcher?.homePkgs ?: emptySet())
                }.also { it.onState = { refreshStatus(); updateVisibility() }; it.start() }
            }
        } else {
            rootWatcher?.stop(); rootWatcher = null
        }
        refreshStatus()
    }

    // ───────── 사이드바 ─────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun barAlpha() = (Prefs.opacity(this).coerceIn(50, 100)) / 100f

    fun screenSize(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun removeBar() {
        val r = barRoot ?: return
        runCatching { barWm?.removeView(r) }
        barRoot = null; barView = null; barLp = null; shown = false
    }

    /** 사이드바 창 만들기. 접근성이 켜져 있으면 키보드보다 위에 뜨는 창으로 */
    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildBar() {
        removeBar()
        val a11y = A11yService.instance
        val bwm = a11y?.getSystemService(WindowManager::class.java) ?: wm
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                   else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val ctx: Context = a11y ?: this

        val (w, h) = screenSize()
        val left = Prefs.sideLeft(this)
        val buttonMode = Prefs.buttonMode(this)
        isButton = buttonMode
        isLeft = left

        val root = FrameLayout(ctx)
        val bar: View
        val lp: WindowManager.LayoutParams
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        if (buttonMode) {
            // ── 버튼식: 떠 있는 동그란 유리 버튼 (꾹 눌러서 어디든 옮기기) ──
            val size = dp(52)
            bar = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(150, 110, 110, 110))
                    setStroke(dp(1), Color.argb(150, 255, 255, 255))
                }
                elevation = dp(4).toFloat()
                alpha = barAlpha()
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_touch)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }, FrameLayout.LayoutParams(-1, -1))
            }
            root.addView(bar, FrameLayout.LayoutParams(size, size))
            val bx = Prefs.btnX(this).takeIf { it >= 0 } ?: (w - size - dp(16))
            val by = Prefs.btnY(this).takeIf { it >= 0 } ?: ((h * 0.6f).toInt())
            lp = WindowManager.LayoutParams(size, size, type, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bx.coerceIn(0, w - size)
                y = by.coerceIn(0, h - size)
            }
            root.setOnTouchListener(ButtonTouch())
        } else {
            // ── 사이드바식: 화면 가장자리 얇은 막대 (왼쪽/오른쪽) ──
            val touchW = maxOf(dp(14), dp(Prefs.barW(this)) + dp(8))   // 실제 터치 폭 (가장자리만)
            val barW = dp(Prefs.barW(this))                          // 보이는 바 두께 (설정)
            val barH = (h * Prefs.barLen(this) / 100f).toInt()      // 바 길이 (설정)
            val centerY = (h * Prefs.barPos(this) / 100f).toInt()   // 세로 위치 (설정)
            bar = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = barW / 2f
                    setColor(Color.rgb(128, 128, 128))          // 중간 회색
                    setStroke(dp(1), Color.argb(170, 255, 255, 255))
                }
                alpha = barAlpha()
                elevation = dp(2).toFloat()
            }
            val side = if (left) Gravity.START else Gravity.END
            root.addView(bar, FrameLayout.LayoutParams(barW, barH, side or Gravity.CENTER_VERTICAL)
                .apply { if (left) marginStart = dp(3) else marginEnd = dp(3) })
            lp = WindowManager.LayoutParams(touchW, barH, type, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or side
                x = 0
                y = (centerY - barH / 2).coerceIn(0, h - barH)
            }
            root.setOnTouchListener(SwipeTouch())
        }

        // 바 영역은 시스템 뒤로가기 제스처에서 제외
        if (!buttonMode) root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) {
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
        root.visibility = View.GONE

        runCatching { bwm.addView(root, lp) }.onFailure {
            Toast.makeText(this, "사이드바를 띄우지 못했어요: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        barWm = bwm; barRoot = root; barView = bar; barLp = lp
        updateVisibility(animate = false)
    }

    /** 홈이 아니면 보이기, 홈이면 숨기기 */
    private fun updateVisibility(animate: Boolean = true) {
        val noDetect = A11yService.instance == null && rootWatcher?.rootOk == false
        // 잠금화면이거나 상단 알림창이 내려와 있으면 바로 숨김 (패널도 닫음)
        if (locked || A11yService.shadeOpen) {
            if (panel != null) { lastCloseReason = if (locked) "잠금화면" else "상단 알림창 열림" }
            panel?.dismissNow(); panel = null
            setShown(false, animate = false)
            return
        }
        val want = forceShow || noDetect || !A11yService.onHome
        // 홈으로 가면 패널도 바로 사라짐 (답장 중이어도 키보드와 함께 닫음)
        if (!want && panel != null) {
            lastCloseReason = "홈 화면 감지"
            panel?.dismissNow(); panel = null
        }
        // 숨길 때(홈으로 갈 때)는 애니메이션 없이 바로
        setShown(want, animate && want)
    }

    private fun setShown(show: Boolean, animate: Boolean) {
        val root = barRoot ?: return
        val bar = barView ?: return
        if (show == shown && root.visibility == (if (show) View.VISIBLE else View.GONE)) return
        shown = show
        refreshStatus()
        bar.animate().cancel()
        val off = if (isLeft) -dp(14).toFloat() else dp(14).toFloat()
        if (show) {
            root.visibility = View.VISIBLE
            if (!isButton) handler.postDelayed({ sampleBackground() }, 350)
            if (animate && isButton) {
                bar.scaleX = 0.6f; bar.scaleY = 0.6f; bar.alpha = 0f
                bar.animate().scaleX(1f).scaleY(1f).alpha(barAlpha()).setDuration(220)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else if (animate) {
                bar.translationX = off
                bar.animate().translationX(0f).setDuration(260)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else { bar.translationX = 0f; bar.scaleX = 1f; bar.scaleY = 1f; bar.alpha = barAlpha() }
        } else {
            if (animate) {
                bar.animate().translationX(off).setDuration(180)
                    .withEndAction { if (!shown) root.visibility = View.GONE }.start()
            } else root.visibility = View.GONE
        }
    }

    /** 설정 바꾸는 동안 3초간 보이기 (슬라이더 움직일 때마다 연장) */
    private val endPreview = Runnable { forceShow = false; updateVisibility() }
    private fun previewBar() {
        forceShow = true
        updateVisibility(animate = false)
        handler.removeCallbacks(endPreview)
        handler.postDelayed(endPreview, 3000)
    }

    /** 설정 화면에서 홈이어도 5초간 사이드바 보이기 (보이는지 확인용) */
    fun testShow() {
        forceShow = true
        updateVisibility()
        handler.postDelayed({ forceShow = false; updateVisibility() }, 5000)
    }

    /**
     * 사이드바 터치
     * - 왼쪽으로 밀기 → 패널 열기
     * - 그 외(탭, 위아래 스와이프) → 같은 터치를 밑에 있는 앱에 그대로 다시 넣어줌
     */
    private inner class SwipeTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downT = 0L
        private var fired = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downT = e.eventTime; fired = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val inward = if (isLeft) dx > slop * 2 else dx < -slop * 2
                    if (!fired && inward && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        fired = true
                        val nudge = if (isLeft) dp(10).toFloat() else -dp(10).toFloat()
                        barView?.animate()?.translationX(nudge)?.setDuration(120)
                            ?.withEndAction { barView?.animate()?.translationX(0f)?.setDuration(200)?.start() }
                            ?.start()
                        openPanel(v)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!fired) passThrough(downX, downY, e.rawX, e.rawY, e.eventTime - downT)
                }
            }
            return true
        }
    }

    /** 버튼식: 톡 누르면 패널, 꾹 누른 채 끌면 화면 어디든 이동 (손 떼면 그 자리 저장) */
    private inner class ButtonTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private val longMs = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var moved = false
        private val startDrag = Runnable {
            dragging = true
            barView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            barView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(120)?.start()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = barLp ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; moved = false
                    barView?.animate()?.scaleX(0.92f)?.scaleY(0.92f)?.setDuration(80)?.start()
                    handler.postDelayed(startDrag, longMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        // 길게 누르기 전에 움직이면 이동 아님
                        moved = true
                        handler.removeCallbacks(startDrag)
                    }
                    if (dragging) {
                        val (w, h) = screenSize()
                        lp.x = (startX + dx).toInt().coerceIn(0, w - lp.width)
                        lp.y = (startY + dy).toInt().coerceIn(0, h - lp.height)
                        runCatching { barWm?.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(startDrag)
                    barView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
                    if (dragging) {
                        Prefs.get(this@OverlayService).edit()
                            .putInt(Prefs.KEY_BTN_X, lp.x).putInt(Prefs.KEY_BTN_Y, lp.y).apply()
                    } else if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        openPanel(v)
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    /** 잠깐 터치를 통과시키고 같은 동작을 다시 넣은 뒤 원래대로 */
    private fun passThrough(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val root = barRoot ?: return
        val lp = barLp ?: return
        val bwm = barWm ?: return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = lp.flags or f
        runCatching { bwm.updateViewLayout(root, lp) }
        val restore = {
            handler.postDelayed({
                lp.flags = lp.flags and f.inv()
                runCatching { if (barRoot === root) bwm.updateViewLayout(root, lp) }
            }, 60)
            Unit
        }
        // 창 플래그가 반영될 시간을 조금 준 뒤 다시 넣기
        handler.postDelayed({
            val a11y = A11yService.instance
            val ok = a11y?.replay(x1, y1, x2, y2, dur, restore) ?: false
            if (!ok) {
                // 접근성 없으면 미리 열어둔 루트 셸로 바로 전달
                val moved = kotlin.math.hypot(x2 - x1, y2 - y1) > slopPx()
                val cmd = if (moved) "input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} ${dur.coerceIn(40, 1500)}"
                          else "input tap ${x1.toInt()} ${y1.toInt()}"
                RootShell.run(cmd)
                // input 명령이 실행될 시간만큼 기다렸다가 다시 터치 받기
                handler.postDelayed({ restore() }, 450)
            }
        }, 30)
    }

    private fun slopPx() = ViewConfiguration.get(this).scaledTouchSlop

    // ───────── 배경 밝기에 맞춰 바 명암 바꾸기 ─────────
    private var barDark: Boolean? = null
    private val sampler = object : Runnable {
        override fun run() {
            if (shown && panel == null && !opening) sampleBackground()   // 패널 열려 있을 땐 캡처 양보
            handler.postDelayed(this, 1500)
        }
    }

    private fun sampleBackground() {
        if (isButton) return
        val a11y = A11yService.instance ?: return
        val lp = barLp ?: return
        val (w, _) = screenSize()
        // 바 바로 왼쪽 배경 영역
        val area = if (isLeft) Rect(dp(16), lp.y, dp(44), lp.y + lp.height)
                   else Rect(w - dp(44), lp.y, w - dp(16), lp.y + lp.height)
        a11y.sampleLuminance(area) { lum -> lum?.let { applyBarTone(it > 0.55f) } }
    }

    /** 밝은 배경 → 진한 바, 어두운 배경 → 밝은 바 (부드럽게 전환) */
    private fun applyBarTone(dark: Boolean) {
        if (dark == barDark) return
        barDark = dark
        val bg = barView?.background as? GradientDrawable ?: return
        val from = if (dark) Color.rgb(225, 225, 225) else Color.rgb(60, 60, 60)
        val to = if (dark) Color.rgb(60, 60, 60) else Color.rgb(225, 225, 225)
        val strokeTo = if (dark) Color.argb(140, 255, 255, 255) else Color.argb(120, 0, 0, 0)
        bg.setStroke(dp(1), strokeTo)
        android.animation.ValueAnimator.ofArgb(from, to).apply {
            duration = 300
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    // ───────── 패널 ─────────
    private var opening = false

    private fun checkHomeNow() {
        val a11y = A11yService.instance ?: return
        if (a11y.homeVisibleNow()) {
            if (panel != null) { lastCloseReason = "홈 화면 감지"; panel?.dismissNow(); panel = null }
            setShown(false, animate = false)
        }
    }

    private val panelHomeWatch = object : Runnable {
        override fun run() {
            if (panel == null) return
            checkHomeNow()
            if (panel != null) handler.postDelayed(this, 400)
        }
    }

    private fun openPanel(from: View) {
        if (panel?.isShowing == true || opening) return
        opening = true
        val loc = IntArray(2)
        from.getLocationOnScreen(loc)
        val anchorY = loc[1] + from.height / 2
        val anchorX = loc[0] + from.width / 2
        val (_, sh) = screenSize()
        val panelBottom = (sh * 0.778f).toInt()       // 패널 아래 끝 고정 위치
        val host: Context = A11yService.instance ?: this
        val show = { shot: android.graphics.Bitmap? ->
            opening = false
            if (panel?.isShowing != true && shown) {
                panel = PanelDialog(
                    host, anchorY, panelBottom, Prefs.blur(this), anchorX, shot, Prefs.panelClear(this)
                ).also {
                    it.setOnDismissListener { panel = null; refreshStatus(); HeadsUp.restore(this) }
                    it.showAnimated()
                }
                handler.removeCallbacks(panelHomeWatch)
                handler.postDelayed(panelHomeWatch, 600)
                // 패널이 열린 동안 상단 배너 끄기 (새 알림은 패널에만)
                HeadsUp.suppress(this)
            }
        }
        // 뒤 화면을 먼저 찍어 두고(뿌연 배경용) 패널 열기. 접근성이 없으면 바로 열기
        val a11y = A11yService.instance
        if (a11y != null) {
            var done = false
            a11y.captureScreen({ bmp -> if (!done) { done = true; show(bmp) } })
            handler.postDelayed({ if (!done) { done = true; show(null) } }, 450)
        } else show(null)
    }


}
