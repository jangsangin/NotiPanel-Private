package com.leseraphim.notipanel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** 설정 화면 (ColorOS 설정 앱 모양) */
class SettingsActivity : AppCompatActivity() {

    private lateinit var p: OsPalette
    private lateinit var content: LinearLayout

    private lateinit var rowOverlay: Row
    private lateinit var rowListener: Row
    private lateinit var rowA11y: Row
    private lateinit var rowFix: Row
    private lateinit var keepInfo: TextView
    private lateinit var homeInfo: TextView
    private lateinit var swEnable: OsSwitch

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = OsPalette(this)

        // 화면 끝까지 그리기 + 상단바 아이콘 색
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.night
            isAppearanceLightNavigationBars = !p.night
        }

        buildScreen()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ───────── 화면 구성 ─────────

    private fun buildScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // 위쪽 고정 제목줄 (스크롤하면 뒤가 살짝 비치는 배경이 생김)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val headerBg = ColorDrawable(p.bg).apply { alpha = 0 }
        header.background = headerBg
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(p.text)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.card) }
            elevation = dp(3f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { finish() }
            contentDescription = "뒤로"
        }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this).apply {
            text = "노티패널 설정"
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, 0, 0)
        }
        header.addView(title)
        root.addView(header, FrameLayout.LayoutParams(-1, dp(64)))

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            headerBg.alpha = ((y / dp(40f)).coerceIn(0f, 1f) * 240).toInt()
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            header.setPadding(dp(16) + bars.left, bars.top, dp(16) + bars.right, 0)
            header.layoutParams = (header.layoutParams as FrameLayout.LayoutParams).apply {
                height = bars.top + dp(64)
            }
            scroll.setPadding(bars.left, bars.top + dp(64), bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        fillContent()
    }

    private fun fillContent() {
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        label("v$ver · Powered By LeSeraphim", top = 8)

        // ── 켜기 ──
        card {
            swEnable = switchRow(it, "사이드바 켜기", null, false) { on ->
                if (on && !Settings.canDrawOverlays(this)) {
                    swEnable.setChecked(false, true)
                    Toast.makeText(this, "먼저 '다른 앱 위에 표시'를 허용해 주세요", Toast.LENGTH_SHORT).show()
                    return@switchRow
                }
                Prefs.get(this).edit().putBoolean(Prefs.KEY_ENABLED, on).apply()
                if (on) OverlayService.start(this) else OverlayService.stop(this)
            }
            navRow(it, "사이드바 5초간 보이기", "위치·크기를 바꾼 뒤 확인할 때") {
                val svc = OverlayService.instance
                if (svc == null) Toast.makeText(this, "먼저 '사이드바 켜기'를 켜 주세요", Toast.LENGTH_SHORT).show()
                else svc.testShow()
            }
        }

        // ── 권한 ──
        label("권한")
        card {
            rowOverlay = navRow(it, "다른 앱 위에 표시", null) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            rowListener = navRow(it, "알림 접근", null) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            rowA11y = navRow(it, "접근성", "키보드 위 표시 · 터치 즉시 전달 · 배경 따라 명암 (추천)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            rowFix = navRow(it, "권한 한 번에 복구", "루트로 꺼진 권한을 다시 켜요") {
                Toast.makeText(this, "루트로 권한 복구 중…", Toast.LENGTH_SHORT).show()
                PermissionFixer.restore(this) { ok ->
                    runOnUiThread {
                        Toast.makeText(
                            this, if (ok) "권한을 다시 켰어요" else "실패: KernelSU에서 루트 허용을 확인해 주세요",
                            Toast.LENGTH_LONG
                        ).show()
                        refreshState()
                    }
                }
            }
            rowFix.title.setTextColor(p.blue)
            rowFix.setVisible(false)
        }
        keepInfo = footer("").apply { setTextIsSelectable(true) }

        // ── 모양 ──
        label("모양")
        lateinit var leftRow: Row
        lateinit var swLeft: OsSwitch
        lateinit var modeInfo: TextView
        fun showModeInfo(button: Boolean) {
            modeInfo.text = if (button) "버튼식: 톡 누르면 패널, 꾹 누른 채 끌면 화면 어디든 옮길 수 있어요"
                            else "사이드바식: 화면 가장자리 막대를 안쪽으로 밀면 패널이 열려요"
            swLeft.isEnabled = !button
            leftRow.view.isEnabled = !button
            leftRow.title.alpha = if (button) 0.35f else 1f
        }
        card {
            segmentRow(it, listOf("사이드바식", "버튼식"), if (Prefs.buttonMode(this)) 1 else 0) { i ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_BUTTON_MODE, i == 1).apply()
                showModeInfo(i == 1)
            }
            swLeft = switchRow(it, "사이드바를 왼쪽에", null, Prefs.sideLeft(this)) { c ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_SIDE_LEFT, c).apply()
            }
            leftRow = lastRow!!
        }
        modeInfo = footer("")
        showModeInfo(Prefs.buttonMode(this))

        // ── 사이드바 ──
        label("사이드바")
        card {
            sliderRow(it, "진하기", null, 50, 100, Prefs.opacity(this), Prefs.KEY_OPACITY) { "$it%" }
            sliderRow(it, "위치", "화면 위에서부터", 5, 95, Prefs.barPos(this), Prefs.KEY_BAR_POS) { "$it%" }
            sliderRow(it, "길이", "화면 높이 기준", 3, 30, Prefs.barLen(this), Prefs.KEY_BAR_LEN) { "$it%" }
            sliderRow(it, "두께", null, 2, 14, Prefs.barW(this), Prefs.KEY_BAR_W) { "${it}dp" }
        }

        // ── 패널 ──
        label("알림 패널")
        card {
            switchRow(it, "아래 알림 겹치기 효과", "알림이 많을 때 X 버튼 줄에 카드가 쌓여요", Prefs.stackOn(this)) { c ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_STACK, c).apply()
            }
            switchRow(it, "고정 알림 숨기기", "음악·충전·실행 중 같은 지울 수 없는 알림", Prefs.hideOngoing(this)) { c ->
                Prefs.get(this).edit().putBoolean(Prefs.KEY_HIDE_ONGOING, c).apply()
            }
        }
        label("블러 관련")
        card {
            sliderRow(it, "노티바 진하기", "0 = 밝은 유리, 100 = 짙은 회색", 0, 100, Prefs.cardDark(this), Prefs.KEY_CARD) { "$it%" }
            sliderRow(it, "패널 블러(뿌옇기)", null, 0, 150, Prefs.blur(this), Prefs.KEY_BLUR) { "$it" }
            sliderRow(it, "패널 투명도", "높을수록 뒤 화면이 잘 비쳐요", 0, 100, Prefs.panelClear(this), Prefs.KEY_CLEAR) { "$it%" }
        }

        // ── 상태 ──
        label("상태")
        homeInfo = footer("", top = 0)
        val wm = getSystemService(WindowManager::class.java)
        footer(when {
            Build.VERSION.SDK_INT < 31 -> "이 기기는 안드로이드 12 미만이라 블러 대신 반투명 배경을 써요."
            !wm.isCrossWindowBlurEnabled -> "지금은 시스템 블러가 꺼져 있어요(절전 모드 등). 반투명 배경으로 표시돼요."
            else -> "블러 지원됨"
        }, top = 4)
        footer("홈 화면에서는 아무것도 안 떠요. 다른 앱을 켜면 화면 끝에 사이드바가 나오고, 안쪽으로 밀면 알림 패널이 열려요.", top = 4)
    }

    // ───────── 구성 도우미 ─────────

    /** 카드 안 한 줄 */
    inner class Row(val view: LinearLayout, val title: TextView, val sub: TextView, val value: TextView) {
        fun setVisible(v: Boolean) {
            view.visibility = if (v) View.VISIBLE else View.GONE
            (view.tag as? View)?.visibility = view.visibility      // 위 구분선도 함께
        }
    }
    private var lastRow: Row? = null

    private fun label(text: String, top: Int = 18) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(p.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(top), dp(12), dp(8))
        })
    }

    private fun footer(text: String, top: Int = 8): TextView = TextView(this).apply {
        this.text = text
        setTextColor(p.sub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
        setPadding(dp(12), dp(top), dp(12), 0)
        content.addView(this)
    }

    private fun card(build: (LinearLayout) -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(p.card); cornerRadius = dp(24f) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(24f))
            }
            setPadding(0, dp(4), 0, dp(4))
        }
        build(c)
        content.addView(c)
    }

    private fun addToCard(card: LinearLayout, row: View) {
        if (card.childCount > 0) {
            val line = View(this).apply { setBackgroundColor(p.divider) }
            card.addView(line, LinearLayout.LayoutParams(-1, maxOf(1, dp(0.6f).toInt())).apply {
                marginStart = dp(20); marginEnd = dp(20)
            })
            row.tag = line
        }
        card.addView(row)
    }

    private fun baseRow(card: LinearLayout, title: String, sub: String?): Row {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = RippleDrawable(ColorStateList.valueOf(p.pressed), null, ColorDrawable(Color.WHITE))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(this).apply {
            text = title
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        val s = TextView(this).apply {
            text = sub ?: ""
            visibility = if (sub == null) View.GONE else View.VISIBLE
            setTextColor(p.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(3), 0, 0)
        }
        texts.addView(t); texts.addView(s)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this).apply {
            setTextColor(p.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(6), 0)
        }
        row.addView(v)
        addToCard(card, row)
        return Row(row, t, s, v).also { lastRow = it }
    }

    private fun navRow(card: LinearLayout, title: String, sub: String?, onClick: () -> Unit): Row {
        val r = baseRow(card, title, sub)
        r.view.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_right)
            imageTintList = ColorStateList.valueOf(p.sub)
            alpha = 0.6f
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        r.view.setOnClickListener { onClick() }
        return r
    }

    private fun switchRow(card: LinearLayout, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): OsSwitch {
        val r = baseRow(card, title, sub)
        r.value.visibility = View.GONE
        val sw = OsSwitch(this, p).apply { setChecked(checked); this.onChange = onChange }
        r.view.addView(sw)
        r.view.setOnClickListener { sw.toggle() }
        return sw
    }

    /** 제목·값 한 줄 + 아래 두꺼운 슬라이드바 */
    private fun sliderRow(
        card: LinearLayout, title: String, sub: String?, from: Int, to: Int, value: Int, key: String,
        fmt: (Int) -> String
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title; setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        if (sub != null) texts.addView(TextView(this).apply {
            text = sub; setTextColor(p.sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(3), 0, 0)
        })
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this).apply {
            setTextColor(p.blue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = fmt(value.coerceIn(from, to))
        }
        top.addView(v)
        box.addView(top)
        val slider = OsSlider(this, p, from, to, value).apply {
            onChange = { i ->
                v.text = fmt(i)
                Prefs.get(this@SettingsActivity).edit().putInt(key, i).apply()
            }
        }
        box.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addToCard(card, box)
    }

    /** 두 개 중 하나 고르는 알약 모양 버튼 */
    private fun segmentRow(card: LinearLayout, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val wrap = FrameLayout(this).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(p.off); cornerRadius = dp(20f) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val pill = View(this).apply {
            background = GradientDrawable().apply {
                setColor(if (p.night) Color.parseColor("#636366") else Color.WHITE); cornerRadius = dp(17f)
            }
            elevation = dp(1f)
        }
        val labels = LinearLayout(this)
        var cur = selected
        val tvs = options.mapIndexed { i, s ->
            TextView(this).apply {
                text = s
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }.also { labels.addView(it, LinearLayout.LayoutParams(0, dp(36), 1f)) }
        }
        fun paintAll() = tvs.forEachIndexed { i, t ->
            t.setTextColor(if (i == cur) p.text else p.sub)
            t.typeface = Typeface.create(if (i == cur) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
        paintAll()
        tvs.forEachIndexed { i, t ->
            t.setOnClickListener {
                if (cur == i) return@setOnClickListener
                cur = i
                pill.animate().translationX(pill.width.toFloat() * i).setDuration(220)
                    .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f)).start()
                paintAll()
                t.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onSelect(i)
            }
        }
        track.addView(pill, FrameLayout.LayoutParams(0, dp(36)))
        track.addView(labels, FrameLayout.LayoutParams(-1, dp(36)))
        track.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = (r - l - dp(6)) / options.size
            if (pill.layoutParams.width != w) {
                pill.layoutParams = pill.layoutParams.apply { width = w }
                pill.translationX = w.toFloat() * cur
            }
        }
        wrap.addView(track, FrameLayout.LayoutParams(-1, -2))
        addToCard(card, wrap)
    }

    // ───────── 상태 갱신 ─────────

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val overlayOk = Settings.canDrawOverlays(this)
        rowOverlay.value.text = if (overlayOk) "허용됨" else "허용 필요"
        rowOverlay.value.setTextColor(if (overlayOk) p.sub else p.blue)

        val cn = ComponentName(this, NotiListenerService::class.java).flattenToString()
        val listenerOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(cn) == true
        rowListener.value.text = if (listenerOk) "허용됨" else "허용 필요"
        rowListener.value.setTextColor(if (listenerOk) p.sub else p.blue)

        val a11yCn = ComponentName(this, A11yService::class.java).flattenToString()
        val a11yOk = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(a11yCn) == true
        rowA11y.value.text = if (a11yOk) "켜짐" else "꺼짐"
        rowA11y.value.setTextColor(if (a11yOk) p.sub else p.blue)

        // 루트 되는 폰에서만 루트 버튼 보이기 + 권한 자동 유지 상태 안내
        val secureOk = PermissionFixer.hasSecureSettings(this)
        PermissionFixer.checkRoot { rooted ->
            runOnUiThread {
                rowFix.setVisible(rooted)
                keepInfo.text = when {
                    secureOk -> "재부팅 후 권한 자동 유지: 켜짐 ✓"
                    rooted -> "루트로 재부팅 때마다 권한을 자동 복구해요"
                    else -> "재부팅 후 접근성이 풀리면, PC에 폰을 연결하고 아래 명령을 한 번만 실행하세요. 그다음부턴 앱이 스스로 다시 켜요.\n\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                }
            }
        }

        homeInfo.text = A11yService.status()

        val on = Prefs.enabled(this) && overlayOk
        swEnable.setChecked(on)
        if (on) OverlayService.start(this)
    }
}
