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
        private const val CHANNEL = "overlay"
        private const val NOTI_ID = 1

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
    private var shown = false
    private var forceShow = false
    private var panel: PanelDialog? = null
    private var rootWatcher: RootHomeWatcher? = null
    private val handler = Handler(Looper.getMainLooper())

    private val homeListener: (Boolean) -> Unit = { updateVisibility() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_OPACITY) barView?.alpha = barAlpha()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        startAsForeground()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        A11yService.addListener(homeListener)
        A11yService.onConnectionChanged = { rebuildBar(); updateWatcher() }
        updateWatcher()
        rebuildBar()
        if (A11yService.instance == null) {
            Toast.makeText(this, "접근성을 켜야 사이드바가 키보드 위에 보여요", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        A11yService.removeListener(homeListener)
        A11yService.onConnectionChanged = null
        rootWatcher?.stop()
        handler.removeCallbacksAndMessages(null)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        panel?.dismissNow()
        removeBar()
        super.onDestroy()
    }

    // ───────── 상주 알림 ─────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "사이드바", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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

    // ───────── 홈 감지 (접근성 없으면 루트) ─────────
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

    // ───────── 사이드바 ─────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun barAlpha() = (Prefs.opacity(this).coerceAtLeast(30)) / 100f

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

        val (_, h) = screenSize()
        val touchW = dp(28)                 // 실제 터치 폭
        val barW = dp(6)                    // 보이는 바 두께
        val barH = (h * 0.12f).toInt()      // 바 길이
        val centerY = (h * 0.73f).toInt()   // 화면 위에서 73% 지점 (키보드 모음 자리)

        val root = FrameLayout(ctx)
        val bar = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = barW / 2f
                setColor(Color.rgb(128, 128, 128))          // 중간 회색
                setStroke(dp(1), Color.argb(170, 255, 255, 255))
            }
            alpha = barAlpha()
            elevation = dp(2).toFloat()
        }
        root.addView(bar, FrameLayout.LayoutParams(barW, barH, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { marginEnd = dp(4) })

        val lp = WindowManager.LayoutParams(
            touchW, barH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = centerY - barH / 2
        }

        // 바 영역은 시스템 뒤로가기 제스처에서 제외
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= 29) {
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
        root.setOnTouchListener(SwipeTouch())
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
        val want = forceShow || !A11yService.onHome
        if (!want) panel?.closeAnimated()
        setShown(want, animate)
    }

    private fun setShown(show: Boolean, animate: Boolean) {
        val root = barRoot ?: return
        val bar = barView ?: return
        if (show == shown && root.visibility == (if (show) View.VISIBLE else View.GONE)) return
        shown = show
        bar.animate().cancel()
        val off = dp(14).toFloat()
        if (show) {
            root.visibility = View.VISIBLE
            if (animate) {
                bar.translationX = off
                bar.animate().translationX(0f).setDuration(260)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
            } else bar.translationX = 0f
        } else {
            if (animate) {
                bar.animate().translationX(off).setDuration(180)
                    .withEndAction { if (!shown) root.visibility = View.GONE }.start()
            } else root.visibility = View.GONE
        }
    }

    /** 설정 화면에서 홈이어도 5초간 사이드바 보이기 (보이는지 확인용) */
    fun testShow() {
        forceShow = true
        updateVisibility()
        handler.postDelayed({ forceShow = false; updateVisibility() }, 5000)
    }

    /** 사이드바: 왼쪽으로 밀면 패널 열기 (탭은 무시) */
    private inner class SwipeTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var downX = 0f
        private var fired = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; fired = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    // 끌려 나오는 느낌
                    barView?.translationX = dx.coerceIn(-dp(16).toFloat(), 0f)
                    if (!fired && dx < -slop * 2) {
                        fired = true
                        openPanel(v)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    barView?.animate()?.translationX(0f)?.setDuration(200)?.start()
                }
            }
            return true
        }
    }

    // ───────── 패널 ─────────
    private fun openPanel(from: View) {
        if (panel?.isShowing == true) return
        val loc = IntArray(2)
        from.getLocationOnScreen(loc)
        val start = Rect(loc[0], loc[1], loc[0] + from.width, loc[1] + from.height)

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
