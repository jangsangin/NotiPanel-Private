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
    private var button: FrameLayout? = null
    private lateinit var buttonLp: WindowManager.LayoutParams
    private var panel: PanelDialog? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_OPACITY, Prefs.KEY_SIZE -> applyButtonStyle()
            Prefs.KEY_POS_X, Prefs.KEY_POS_Y -> if (Prefs.posX(this) < 0) placeDefault()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addButton()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panel?.dismissNow()
        button?.let { runCatching { wm.removeView(it) } }
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
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66FFFFFF)
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

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
        wm.addView(root, buttonLp)
    }

    private fun applyButtonStyle(addFirst: Boolean = false) {
        val b = button ?: return
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
        if (!addFirst) runCatching { wm.updateViewLayout(b, buttonLp) }
    }

    /** 기본 위치 = 사진에 표시한 오른쪽 아래 동그라미 자리 */
    private fun placeDefault(update: Boolean = true) {
        val (w, h) = screenSize()
        val size = buttonLp.width
        buttonLp.x = (w * 0.88f).toInt() - size / 2
        buttonLp.y = (h * 0.824f).toInt() - size / 2
        if (update) button?.let { runCatching { wm.updateViewLayout(it, buttonLp) } }
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
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val (w, h) = screenSize()
                        buttonLp.x = (startX + dx).toInt().coerceIn(0, w - buttonLp.width)
                        buttonLp.y = (startY + dy).toInt().coerceIn(0, h - buttonLp.height)
                        wm.updateViewLayout(v, buttonLp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        Prefs.get(this@OverlayService).edit()
                            .putInt(Prefs.KEY_POS_X, buttonLp.x)
                            .putInt(Prefs.KEY_POS_Y, buttonLp.y)
                            .apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        openPanel(v)
                    }
                }
            }
            return true
        }
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

        panel = PanelDialog(this, start, end, Prefs.blur(this)).also {
            it.setOnDismissListener { panel = null }
            it.showAnimated()
        }
    }
}
