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
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/** 화면 위에 터치 아이콘을 띄우고, 누르면 패널을 여는 서비스 */
class OverlayService : Service() {

    companion object {
        private const val CHANNEL = "overlay"
        private const val NOTI_ID = 1

        fun start(c: Context) {
            val i = Intent(c, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    /** 버튼 창을 붙이는 WindowManager (접근성 켜지면 접근성 쪽 → 키보드 위에 뜸) */
    private lateinit var bwm: WindowManager
    private var button: FrameLayout? = null
    private lateinit var buttonLp: WindowManager.LayoutParams
    private var panel: PanelDialog? = null
    private var rootWatcher: RootHomeWatcher? = null

    private fun updateWatcher() {
        if (A11yService.instance == null) {
            if (rootWatcher == null) {
                rootWatcher = RootHomeWatcher(this) { pkg ->
                    A11yService.reportFromRoot(pkg, rootWatcher?.homePkgs ?: emptySet())
                }.also { it.start() }
            }
        } else {
            rootWatcher?.stop(); rootWatcher = null
        }
    }
    private lateinit var bg: GradientDrawable
    private lateinit var layer: android.graphics.drawable.LayerDrawable

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_OPACITY, Prefs.KEY_SIZE -> applyButtonStyle()
            Prefs.KEY_POS_X, Prefs.KEY_POS_Y -> if (Prefs.posX(this) < 0 && !hidden) placeDefault()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addButton()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        A11yService.addListener(homeListener)
        A11yService.onConnectionChanged = { rebuildButton(); updateWatcher() }
        updateWatcher()
        if (A11yService.instance == null) {
            android.widget.Toast.makeText(
                this, "접근성을 켜야 사이드바가 키보드 위에 보여요", android.widget.Toast.LENGTH_LONG
            ).show()
        }
        if (!A11yService.onHome) setHidden(true, animate = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        A11yService.removeListener(homeListener)
        A11yService.onConnectionChanged = null
        rootWatcher?.stop()
        handler.removeCallbacksAndMessages(null)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panel?.dismissNow()
        button?.let { runCatching { bwm.removeView(it) } }
        button = null
        super.onDestroy()
    }

    // ───────── 상단바 상주 알림 ─────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "터치 아이콘", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_touch)
            .setContentTitle("노티패널 실행 중")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    // ───────── 터치 아이콘 ─────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val root = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_touch)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))
        bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9999f
            setColor(0x66FFFFFF)
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }
        layer = android.graphics.drawable.LayerDrawable(arrayOf(bg))
        root.background = layer
        // 바 영역은 시스템 뒤로가기 제스처에서 제외
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) {
                v.systemGestureExclusionRects =
                    if (hidden) listOf(Rect(0, 0, v.width, v.height)) else emptyList()
            }
        }

        // 접근성 서비스가 켜져 있으면 키보드보다 위에 뜨는 접근성 오버레이 사용
        val a11y = A11yService.instance
        bwm = a11y?.getSystemService(WindowManager::class.java) ?: wm
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                   else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        buttonLp = WindowManager.LayoutParams(
            0, 0, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        button = root
        applyButtonStyle(addFirst = true)
        root.setOnTouchListener(DragTouch())
        bwm.addView(root, buttonLp)
    }

    private fun applyButtonStyle(addFirst: Boolean = false) {
        val b = button ?: return
        if (hidden) return
        val size = dp(Prefs.sizeDp(this))
        buttonLp.width = size
        buttonLp.height = size
        (b.getChildAt(0) as ImageView).setPadding(size / 4, size / 4, size / 4, size / 4)
        b.alpha = Prefs.opacity(this) / 100f
        val x = Prefs.posX(this)
        val y = Prefs.posY(this)
        if (x < 0 || y < 0) placeDefault(update = false) else {
            buttonLp.x = x; buttonLp.y = y
        }
        if (!addFirst) runCatching { bwm.updateViewLayout(b, buttonLp) }
    }

    /** 기본 위치 = 사진에 표시한 오른쪽 아래 동그라미 자리 */
    private fun placeDefault(update: Boolean = true) {
        val (w, h) = screenSize()
        val size = buttonLp.width
        buttonLp.x = (w * 0.88f).toInt() - size / 2
        buttonLp.y = (h * 0.824f).toInt() - size / 2
        if (update) button?.let { runCatching { bwm.updateViewLayout(it, buttonLp) } }
    }

    /** 끌면 이동, 짧게 누르면 패널 열기 */
    private inner class DragTouch : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = buttonLp.x; startY = buttonLp.y
                    dragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (hidden) {
                        if (!dragging && dx < -slop * 2) {
                            dragging = true
                            openPanel(v)
                        }
                        return true
                    }
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (w, h) = screenSize()
                        buttonLp.x = (startX + dx).toInt().coerceIn(0, w - buttonLp.width)
                        buttonLp.y = (startY + dy).toInt().coerceIn(0, h - buttonLp.height)
                        bwm.updateViewLayout(v, buttonLp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (hidden) {
                        // 숨김 상태에서는 탭 무시 (왼쪽으로 밀 때만 열림)
                    } else if (dragging) {
                        Prefs.get(this@OverlayService).edit()
                            .putInt(Prefs.KEY_POS_X, buttonLp.x)
                            .putInt(Prefs.KEY_POS_Y, buttonLp.y)
                            .apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        onTap(v)
                    }
                }
            }
            return true
        }
    }

    private fun rebuildButton() {
        button?.let { runCatching { bwm.removeView(it) } }
        button = null
        hideAnim?.cancel()
        val wasHidden = hidden
        hidden = false
        addButton()
        if (wasHidden || !A11yService.onHome) setHidden(true, animate = false)
    }

    // ───────── 한 번 터치 = 패널, 두 번 터치 = 화면 끄기 ─────────
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastTap = 0L
    private var pendingOpen: Runnable? = null
    private val doubleTapMs = 260L

    private fun onTap(v: android.view.View) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTap < doubleTapMs && pendingOpen != null) {
            // 두 번 터치 → 화면 끄기
            handler.removeCallbacks(pendingOpen!!)
            pendingOpen = null
            lastTap = 0
            if (!A11yService.lockScreen()) {
                android.widget.Toast.makeText(
                    this, "화면 끄기: 설정에서 접근성을 켜 주세요", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        lastTap = now
        val r = Runnable { pendingOpen = null; openPanel(v) }
        pendingOpen = r
        handler.postDelayed(r, doubleTapMs)
    }

    // ───────── 다른 앱에서는 옆으로 숨기기 ─────────
    private var hidden = false
    private var hideAnim: android.animation.ValueAnimator? = null
    private val homeListener: (Boolean) -> Unit = { home -> setHidden(!home) }

    /**
     * 숨길 때: 아이콘이 얇은 회색 세로 바로 변하면서
     *          화면 오른쪽 끝, 아래에서 30% 높이로 미끄러져 붙음 (터치는 통과)
     * 보일 때: 바가 다시 동그란 아이콘으로 변하며 원래 자리로 돌아옴
     */
    private fun setHidden(hide: Boolean, animate: Boolean = true) {
        val b = button ?: return
        if (hide == hidden) return
        hidden = hide
        if (hide) panel?.closeAnimated()

        val (w, h) = screenSize()
        val icon = b.getChildAt(0)
        val size = dp(Prefs.sizeDp(this))

        // 바 크기·위치 (영상 속 회색 바 비율)
        val barW = dp(6)                       // 눈에 보이는 바 두께
        val touchW = dp(26)                    // 실제로 터치되는 폭
        val edge = dp(4)                       // 화면 끝과 바 사이 간격
        val barH = (h * 0.12f).toInt()
        val barX = w - touchW
        val barY = (h * 0.73f).toInt() - barH / 2

        val homeX = Prefs.posX(this).takeIf { it >= 0 } ?: defaultX()
        val homeY = Prefs.posY(this).takeIf { it >= 0 } ?: ((h * 0.824f).toInt() - size / 2)

        val f = Rect(buttonLp.x, buttonLp.y, buttonLp.x + buttonLp.width, buttonLp.y + buttonLp.height)
        val t = if (hide) Rect(barX, barY, barX + touchW, barY + barH)
                else Rect(homeX, homeY, homeX + size, homeY + size)
        val fromA = b.alpha
        val toA = if (hide) 1f else Prefs.opacity(this) / 100f
        val fromIcon = icon.alpha
        val toIcon = if (hide) 0f else 1f

        hideAnim?.cancel()

        fun frame(p: Float, colorP: Float) {
            buttonLp.x = (f.left + (t.left - f.left) * p).toInt()
            buttonLp.y = (f.top + (t.top - f.top) * p).toInt()
            buttonLp.width = maxOf(1, (f.width() + (t.width() - f.width()) * p).toInt())
            buttonLp.height = maxOf(1, (f.height() + (t.height() - f.height()) * p).toInt())
            val c = colorP.coerceIn(0f, 1f)
            // 동그라미일 땐 인셋 0, 바일 땐 오른쪽에 얇게만 그림
            val insL = ((touchW - barW - edge) * c).toInt()
            val insR = (edge * c).toInt()
            layer.setLayerInset(0, insL, 0, insR, 0)
            bg.cornerRadius = minOf(buttonLp.width - insL - insR, buttonLp.height) / 2f
            // 아이콘(흰 반투명) ↔ 바(연회색)
            // 아이콘(흰 반투명) → 바(중간 회색, 흰 배경에서도 잘 보이게)
            bg.setColor(android.graphics.Color.argb(
                (0x66 + (0xE6 - 0x66) * c).toInt(),
                (255 - 125 * c).toInt(), (255 - 125 * c).toInt(), (255 - 125 * c).toInt()
            ))
            bg.setStroke(dp(1), if (c > 0.5f) 0x66FFFFFF else 0x88FFFFFF.toInt())
            b.alpha = fromA + (toA - fromA) * p.coerceIn(0f, 1f)
            icon.alpha = (fromIcon + (toIcon - fromIcon) * (p * 1.6f).coerceIn(0f, 1f))
            runCatching { bwm.updateViewLayout(b, buttonLp) }
        }

        if (!animate) { frame(1f, if (hide) 1f else 0f); return }
        hideAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (hide) 380 else 460
            interpolator = if (hide) android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f)
                           else android.view.animation.OvershootInterpolator(1.0f)
            addUpdateListener {
                val p = it.animatedValue as Float
                frame(p, if (hide) p else 1f - p)
            }
            start()
        }
    }

    private fun setTouchable(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        buttonLp.flags = if (on) buttonLp.flags and f.inv() else buttonLp.flags or f
    }

    private fun defaultX(): Int {
        val (w, _) = screenSize()
        return (w * 0.88f).toInt() - buttonLp.width / 2
    }

    // ───────── 패널 ─────────
    private fun openPanel(from: android.view.View) {
        if (panel?.isShowing == true) return
        val loc = IntArray(2)
        from.getLocationOnScreen(loc)
        val start = Rect(loc[0], loc[1], loc[0] + from.width, loc[1] + from.height)

        // 사진에 표시한 영역: 좌우 여백 약간, 화면 높이 31% ~ 72%
        val (w, h) = screenSize()
        val margin = dp(14)
        val end = Rect(margin, (h * 0.31f).toInt(), w - margin, (h * 0.72f).toInt())

        val host: Context = A11yService.instance ?: this
        panel = PanelDialog(host, start, end, Prefs.blur(this)).also {
            it.setOnDismissListener { panel = null }
            it.showAnimated()
        }
    }
}
