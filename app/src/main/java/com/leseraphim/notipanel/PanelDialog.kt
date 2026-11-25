package com.leseraphim.notipanel

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Dialog
import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 블러 배경 알림 패널.
 *
 * 성능: 창 크기/위치는 처음에 한 번만 정한다. 열고 닫는 애니메이션은
 * 화면 변형(크기·투명도)과 블러 세기만 바꿔서 GPU에서 부드럽게 돈다.
 */
class PanelDialog(
    private val ctx: Context,
    private val anchorY: Int,      // 사이드바 중앙 높이 (펼쳐지는 기준점)
    private val barBottom: Int,    // 패널 아래 끝 위치 (고정)
    private val blurRadius: Int,
    private val anchorX: Int = -1,     // 펼쳐지는 기준 가로 위치 (사이드바/버튼)
    private val shot: android.graphics.Bitmap? = null,   // 열 때 찍은 뒤 화면 (뿌옇게 깔 배경)
    private val clearness: Int = 40    // 패널 투명도 0~100 (높을수록 뒤가 잘 비침)
) : Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val screenW: Int
    private val screenH: Int
    private val corner = 28 * density

    private lateinit var content: View
    private lateinit var list: RecyclerView
    private lateinit var adapter: NotiAdapter
    private lateinit var emptyView: TextView
    private lateinit var focusSink: View
    private val useShot: Boolean
    private val stackOn = Prefs.stackOn(ctx)
    private var shotView: android.widget.ImageView? = null

    private var closing = false
    private var blurAnim: ValueAnimator? = null
    private val blurOk: Boolean
    private val main = Handler(Looper.getMainLooper())

    // 패널 위치 (화면 좌표)
    private val left: Int
    private val right: Int
    private val bottom: Int
    private var top: Int
    private var baseY = 0

    private val storeListener: () -> Unit = { scheduleRefresh() }

    init {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val b = wm.currentWindowMetrics.bounds
        screenW = b.width(); screenH = b.height()
        left = dp(14); right = screenW - dp(14)
        bottom = barBottom
        top = bottom - maxHeight()
        if (cachedImeHeight <= 0) cachedImeHeight = (screenH * 0.42f).toInt()
        useShot = shot != null && Build.VERSION.SDK_INT >= 31
        blurOk = !useShot && Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled && blurRadius > 0

        val root = FrameLayout(ctx)
        // 둥근 패널 모양으로 안쪽 전부 자르기
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) =
                o.setRoundRect(0, 0, v.width, v.height, corner)
        }
        root.clipToOutline = true
        if (useShot) {
            // 뒤 화면 캡처를 깔고 뿌옇게 → 시스템 블러가 안 돼도 항상 뿌연 유리
            shotView = android.widget.ImageView(ctx).apply {
                setImageBitmap(shot)
                scaleType = android.widget.ImageView.ScaleType.MATRIX
                if (blurRadius > 0) setRenderEffect(
                    RenderEffect.createBlurEffect(blurRadius.toFloat(), blurRadius.toFloat(), Shader.TileMode.CLAMP)
                )
            }
            root.addView(shotView, FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP or Gravity.START))
        }
        // 색 막: 투명도가 낮을수록 진한 회색이 덮임 + 얇은 빛 테두리
        val tintA = ((100 - clearness.coerceIn(0, 100)) * 2.3f).toInt()
        root.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(tintA, 95, 95, 95), Color.argb((tintA + 15).coerceAtMost(255), 55, 55, 55))
            ).apply {
                cornerRadius = corner
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(70, 255, 255, 255))
            }
        }, FrameLayout.LayoutParams(-1, -1))
        content = LayoutInflater.from(ctx).inflate(R.layout.panel, root, false)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        // 입력칸에서 포커스를 뺄 때 받아줄 빈 칸 (목록에 주면 다시 입력칸으로 넘어가 버림)
        focusSink = View(ctx).apply { isFocusable = true; isFocusableInTouchMode = true }
        root.addView(focusSink, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        setCanceledOnTouchOutside(false)
        setupList()
        setupWindow()
    }

    /** 접힌 알림 3개 + 간격 + X 버튼 영역 높이 (딱 맞아서 스크롤이 생기지 않음) */
    private fun threeCardHeight(): Int {
        val gap = dp(10)
        var cardH = 0
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(c)
            if (pos != RecyclerView.NO_POSITION && !adapter.isExpanded(pos)) { cardH = c.height; break }
        }
        if (cardH <= 0) cardH = dp(80)
        return (dp(12) + (cardH + gap) * 3 + list.paddingBottom).coerceAtMost(maxHeight())
    }

    /** 맨 위 카드 3장(펼친 카드 포함)이 전부 들어가는 높이 */
    private fun firstThreeHeight(): Int? {
        val gap = dp(10)
        var sum = 0
        var found = 0
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(c)
            if (pos in 0..2) { sum += c.height + gap; found++ }
        }
        if (found < 3) return null
        return dp(12) + sum + list.paddingBottom
    }

    /** 기본(최대) 패널 높이: 화면의 61.6% (예전 56%에서 10% 키움) */
    /** 캡처 배경을 창 위치만큼 반대로 옮겨서, 패널 뒤 화면과 정확히 겹치게 */
    private fun syncShot(y: Int) {
        val v = shotView ?: return
        v.translationX = -left.toFloat()
        v.translationY = -y.toFloat()
    }

    /** 사이드바/버튼 쪽에서 펼쳐지도록 기준 가로 위치 (기본: 오른쪽 끝) */
    private fun pivotXFor(): Float =
        if (anchorX < 0) (right - left).toFloat()
        else (anchorX - left).toFloat().coerceIn(0f, (right - left).toFloat())

    private fun maxHeight() = (screenH * 0.616f).toInt()

    private fun setupWindow() {
        val w = window ?: return
        w.setType(
            if (ctx is android.accessibilityservice.AccessibilityService)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        )
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        w.setWindowAnimations(0)
        w.setElevation(0f)
        w.setGravity(Gravity.TOP or Gravity.START)
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // 창 자체는 투명 (뿌연 배경·색 막·테두리는 안쪽 뷰들이 담당)
        w.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = corner
            setColor(Color.TRANSPARENT)
        })
        if (blurOk) w.setBackgroundBlurRadius(0)
        w.decorView.setPadding(0, 0, 0, 0)
        w.decorView.alpha = 0f
        placeWindow(top, bottom - top)

        // 키보드가 올라오면 패널을 키보드 위로 올림
        w.decorView.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            moveAboveKeyboard(ime)
            v.onApplyWindowInsets(insets)
        }
    }

    private fun placeWindow(y: Int, h: Int) {
        val w = window ?: return
        val lp = w.attributes
        lp.x = left
        lp.y = y
        lp.width = right - left
        lp.height = h
        w.attributes = lp
        baseY = y
        content.requestLayout()
        syncShot(y)
    }

    // ───────── 키보드 따라 패널 올리기 (키보드 애니메이션과 같은 시간·곡선) ─────────
    private var keyboardWatch = false
    private var imeSeen = false
    private var watchStart = 0L
    private var posAnim: ValueAnimator? = null
    private val imeInterp = PathInterpolator(0.2f, 0f, 0f, 1f)   // 키보드 애니메이션과 비슷한 곡선

    private var replyAnchor: View? = null
    private var savedH = 0

    /** 안쪽 뷰가 속한 목록 카드 */
    private fun cardOf(v: View?): View? {
        var cur: View? = v
        while (cur != null && cur.parent !== list) cur = cur.parent as? View
        return cur
    }

    /** 답장 중인 카드의 아래 끝(창 안 좌표) */
    private fun anchorBottomInWindow(): Int? {
        val v = replyAnchor ?: return null
        if (!v.isAttachedToWindow) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        return loc[1] + v.height
    }

    /** 답장 카드 아래 끝이 키보드 위 '카드 간격(10dp)' 자리에 오도록 하는 창 위치 */
    private fun targetYFor(imeTop: Int): Int {
        val h = window?.attributes?.height ?: 0
        return (imeTop - dp(10) - h).coerceAtLeast(dp(30))
    }

    /** 입력칸 열림/닫힘: 키보드가 움직이기 시작하는 순간 같이 움직임 */
    fun watchKeyboard(on: Boolean, anchor: View? = null) {
        keyboardWatch = on
        main.removeCallbacks(keyboardTick)
        val w = window ?: return
        val closeBtn = content.findViewById<View>(R.id.close)
        if (on) {
            imeSeen = false
            lastIme = 0
            replyAnchor = anchor
            // 답장 카드 윗부분이 잘려 있으면 먼저 그만큼 목록을 내려서 카드 전체가 보이게
            cardOf(anchor)?.let { card -> if (card.top < 0) list.scrollBy(0, card.top) }
            // 창을 답장 카드 바로 아래까지만 남기고 자름 (그 아래 카드·X가 키보드 위를 덮지 않게)
            savedH = w.attributes.height
            val ab = anchorBottomInWindow()
            if (ab != null) {
                val newH = (ab + dp(12)).coerceIn(dp(120), savedH)
                if (newH != savedH) {
                    val lp = w.attributes
                    lp.height = newH
                    w.attributes = lp
                }
            }
            closeBtn.animate().alpha(0f).setDuration(150).start()
            slideWindowTo(targetYFor(screenH - cachedImeHeight), 285)
            main.postDelayed(keyboardTick, 120)
        } else {
            replyAnchor = null
            // 저장해 둔 값 대신 '고정 아래 끝 + 지금 패널 크기'로 정확히 복귀
            val wantH = bottom - top
            if (w.attributes.height != wantH) {
                val lp = w.attributes
                lp.height = wantH
                w.attributes = lp
                content.requestLayout()
            }
            baseY = top
            closeBtn.animate().alpha(1f).setDuration(200).start()
            slideWindowTo(top, 250)
            if (refreshPending) scheduleRefresh()   // 답장하는 동안 미뤄둔 새 알림 반영
        }
    }

    private val keyboardTick = object : Runnable {
        override fun run() {
            if (!keyboardWatch || closing) return
            val a11y = ctx as? A11yService ?: A11yService.instance
            if (a11y != null) {
                val imeTop = a11y.imeTop()
                if (imeTop != null) {
                    imeSeen = true
                    cachedImeHeight = screenH - imeTop
                    val want = targetYFor(imeTop)
                    val cur = window?.attributes?.y ?: want
                    // 예상과 실제가 다를 때만 살짝 보정
                    if (kotlin.math.abs(cur - want) > dp(6) && posAnim?.isRunning != true) slideWindowTo(want, 160)
                } else if (imeSeen) {
                    // 키보드만 닫힘 → 입력칸을 버튼으로 되돌리고 패널도 내려감
                    dropFocus()
                    return
                }
            }
            main.postDelayed(this, 80)
        }
    }

    /** 창 위치만(크기는 그대로) 이동 → 가벼움 */
    private fun slideWindowTo(y: Int, ms: Long) {
        val w = window ?: return
        val from = w.attributes.y
        posAnim?.cancel()
        if (from == y) return
        posAnim = ValueAnimator.ofInt(from, y).apply {
            duration = ms
            interpolator = imeInterp
            addUpdateListener {
                val lp = w.attributes
                lp.y = it.animatedValue as Int
                w.attributes = lp
                syncShot(lp.y)
            }
            start()
        }
    }

    /** 입력칸에서 포커스 빼기 (입력칸으로 다시 돌아가지 않도록 목록에 포커스를 줌) */
    private fun dropFocus() {
        if (currentFocus !is android.widget.EditText) return
        focusSink.requestFocus()
    }

    private var imeShift = 0
    private var lastIme = 0
    private fun moveAboveKeyboard(ime: Int) {
        // 접근성이 있으면 키보드 추적은 그쪽이 담당 (여기선 접근성 없을 때만)
        if (A11yService.instance == null && keyboardWatch && lastIme > 0 && ime == 0) dropFocus()
        lastIme = ime
        if (keyboardWatch) return   // 직접 추적 중이면 그쪽이 담당
        val w = window ?: return
        val h = w.attributes.height
        val wantBottom = if (ime > 0) screenH - ime - dp(8) else bottom
        val shift = (wantBottom - (baseY + h)).coerceAtMost(0)
        if (shift == imeShift) return
        imeShift = shift
        val lp = w.attributes
        lp.y = (baseY + shift).coerceAtLeast(dp(30))
        w.attributes = lp
    }

    // ───────── 열기 ─────────
    fun showAnimated() {
        show()
        var started = false
        val go = {
            if (!started) {
                started = true
                fitHeight()
                // 창 크기 반영 후 다음 프레임에 애니메이션 시작
                content.post { animateOpen() }
            }
        }
        list.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                go()
            }
        })
        main.postDelayed({ go() }, 150)
    }

    /**
     * 패널 높이를 내용에 맞춤: 첫 알림 전체 + 두 번째 알림 + 뒤에 쌓인 카드 여유 + X 버튼.
     * 아래 끝은 고정, 위쪽을 맞춘다. 창 크기는 여기서 한 번만 바뀐다.
     */
    private fun fitHeight(onlyIfChangedBy: Int = 0, growOnly: Boolean = false) {
        if (keyboardWatch) return   // 답장 중(키보드 위로 옮긴 상태)엔 크기·위치 유지
        val gap = dp(10)
        val first = list.getChildAt(0)
        val second = list.getChildAt(1)
        // 뒤에 쌓이는 카드 수만큼만 여유를 둠 (X 버튼과 간격 최소화)
        val stacked = (adapter.itemCount - 2).coerceIn(0, 3)
        // 첫 알림이 답장 가능한 채팅이면: 두 번째 알림은 일부만 보이고 X가 그 30% 정도를 덮음
        val replyMode = adapter.isExpanded(0) && adapter.groups.firstOrNull()?.latest?.notification?.actions
            ?.any { !it.remoteInputs.isNullOrEmpty() } == true
        val xZone = dp(12 + 36)                    // X 버튼 + 아래 여백
        val need = if (first == null) dp(200)
        else if (replyMode)
            dp(12) + first.height + gap +
                (second?.let { (it.height * 0.45f).toInt() + (xZone * 0.7f).toInt() } ?: xZone) +
                dp(8)
        else
            // 상태바 알림창처럼: 두 번째 카드 아래 쌓인 카드들 위로 X가 떠 있음
            dp(12) + first.height + gap +
                (second?.let { it.height + gap } ?: 0) +
                (if (stacked > 0) dp(stacked * 9) else 0) +
                dp(20) + dp(8)
        // 알림이 3개 이상이면: 접힌 알림 3개 + X 버튼이 딱 들어가는 크기로 고정
        // 3개 이상: 기본은 접힌 카드 3장 높이, 위 3장 중 펼친 게 있으면 그만큼 위로 커짐
        val h = if (adapter.itemCount >= 3)
            maxOf(threeCardHeight(), firstThreeHeight() ?: 0).coerceAtMost(maxHeight())
        else need.coerceIn(dp(180), maxHeight())
        val curH = window?.attributes?.height ?: (bottom - top)
        val curY = window?.attributes?.y ?: top
        if (onlyIfChangedBy > 0 && kotlin.math.abs(h - curH) < onlyIfChangedBy &&
            kotlin.math.abs((curY + curH) - bottom) < onlyIfChangedBy) return
        if (growOnly && h <= bottom - top) return
        top = bottom - h
        placeWindow(top, h)
    }

    private fun animateOpen() {
        val d = window?.decorView ?: return
        val h = bottom - top
        // 사이드바 쪽(오른쪽, 사이드바 높이)에서 왼쪽 위로 펼쳐짐
        d.pivotX = pivotXFor()
        d.pivotY = (anchorY - top).toFloat().coerceIn(0f, h.toFloat())
        d.scaleX = 0.2f; d.scaleY = 0.2f; d.alpha = 0f
        d.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(340).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
            .withLayer()
            .start()
        animateBlur(0, blurRadius, 340)
        staggerIn()
    }

    private fun animateBlur(from: Int, to: Int, ms: Long, end: () -> Unit = {}) {
        blurAnim?.cancel()
        if (!blurOk) { main.postDelayed(end, ms); return }
        val w = window ?: return
        blurAnim = ValueAnimator.ofInt(from, to).apply {
            duration = ms
            addUpdateListener { w.setBackgroundBlurRadius(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = end()
            })
            start()
        }
    }

    // ───────── 닫기 ─────────
    fun closeAnimated(reason: String = "기타") {
        if (closing) return
        closing = true
        OverlayService.lastCloseReason = reason
        hideKeyboard()
        val d = window?.decorView ?: return dismissNow()
        d.animate().cancel()
        // 열릴 때의 반대: 사이드바 쪽(오른쪽 아래)으로 접히며 사라짐
        val h = (window?.attributes?.height ?: (bottom - top)).toFloat()
        d.pivotX = pivotXFor()
        d.pivotY = (anchorY - (window?.attributes?.y ?: top)).toFloat().coerceIn(0f, h)
        d.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f)
            .setDuration(340).setInterpolator(PathInterpolator(0.4f, 0f, 0.8f, 0.4f))
            .withLayer()
            .withEndAction { dismissNow() }
            .start()
        val cur = if (blurOk) blurRadius else 0
        animateBlur(cur, 0, 320)
    }

    fun dismissNow() {
        blurAnim?.cancel()
        posAnim?.cancel()
        keyboardWatch = false
        runCatching {   // 답장 중이었으면 키보드도 같이 내림
            val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            window?.decorView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        }
        main.removeCallbacksAndMessages(null)
        if (isShowing) runCatching { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        NotificationStore.addListener(storeListener)
        refreshNow()
    }

    override fun onStop() {
        if (!closing) OverlayService.lastCloseReason = "시스템이 창을 닫음"
        NotificationStore.removeListener(storeListener)
        super.onStop()
    }

    // 패널 바깥을 누르면 닫기
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            val d = window?.decorView
            if (d != null && (event.x < 0 || event.y < 0 || event.x > d.width || event.y > d.height)) return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val d = window?.decorView ?: return false
            val x = event.x.toInt()
            val y = event.y.toInt()
            if (x < 0 || y < 0 || x > d.width || y > d.height) {
                closeAnimated("패널 바깥 터치")
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 뒤로가기: 답장 입력 중이면 입력만 닫고 패널은 유지 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (keyboardWatch && currentFocus is android.widget.EditText) {
            dropFocus()
            return
        }
        closeAnimated("뒤로가기")
    }

    val replying get() = keyboardWatch

    // ───────── 목록 ─────────
    private fun setupList() {
        list = content.findViewById(R.id.list)
        emptyView = content.findViewById(R.id.empty)
        adapter = NotiAdapter(
            ctx,
            onOpen = { openNotification(it) },
            onAction = { a -> sendPending(a.actionIntent) },   // 읽음 등: 패널 유지
            onReply = { a, text -> sendReply(a, text) },
            onReplyOpen = { open, card -> watchKeyboard(open, card) },
            // 화살표로 묶음 펼치기/접기: 나머지 카드는 부드럽게 밀려나고, 필요하면 패널이 커짐
            onToggle = { changed ->
                val apply = {
                    // 펼치면 위로 커지고, 접으면(또는 다른 카드를 펼쳐 자동으로 접히면) 크기가 다시 맞춰짐
                    animateChange(allowShrink = true) { changed.forEach { adapter.notifyItemChanged(it) } }
                    val opened = changed.firstOrNull { adapter.isExpanded(it) }
                    if (opened != null) revealCard(opened)
                }
                if (keyboardWatch) {
                    // 답장 중이면: 먼저 답장 닫고 패널이 원래 자리로 돌아온 뒤 펼침/접힘
                    dropFocus()
                    main.postDelayed({ apply() }, 300)
                } else apply()
            }
        )
        list.layoutManager = object : LinearLayoutManager(ctx) {
            override fun canScrollVertically() = !horizontalLock && super.canScrollVertically()

            // 입력칸에 커서가 들어가도 목록이 저절로 스크롤되지 않게 (카드 윗부분 잘림 방지)
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: android.graphics.Rect,
                immediate: Boolean, focusedChildVisible: Boolean
            ) = false

            override fun onRequestChildFocus(
                parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?
            ) = true

            // 패널 아래로 넘친 카드도 미리 만들어 둠 → 처음 열 때부터 X 줄에 쌓인 모습이 보임
            override fun calculateExtraLayoutSpace(
                state: RecyclerView.State, extraLayoutSpace: IntArray
            ) {
                super.calculateExtraLayoutSpace(state, extraLayoutSpace)
                extraLayoutSpace[1] = maxOf(extraLayoutSpace[1], (260 * density).toInt())
            }
        }
        list.adapter = adapter
        list.itemAnimator = null
        list.setRecyclerListener { vh -> resetCard(vh.itemView); ProgressiveBlur.clear(vh.itemView) }
        list.setHasFixedSize(false)
        // 좌우로 미는 동안에는 다른 카드 위치·크기를 그대로 유지 (재계산 안 함)
        list.viewTreeObserver.addOnPreDrawListener {
            if (!horizontalLock && !swiping && !keyboardWatch) applyStack()
            true
        }
        setupBounce()

        // 좌우로 밀어서 알림 지우기
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder) = 0.35f
            override fun getSwipeEscapeVelocity(default: Float) = default * 0.6f

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val g = adapter.groups.getOrNull(vh.bindingAdapterPosition) ?: return 0
                return if (g.items.any { it.isClearable }) super.getSwipeDirs(rv, vh) else 0
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.bindingAdapterPosition
                val g = adapter.groups.getOrNull(pos) ?: return
                animateChange(allowShrink = true) { adapter.removeAt(pos) }
                NotificationStore.dismissMany(g.items.filter { it.isClearable })
            }

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                swiping = state == ItemTouchHelper.ACTION_STATE_SWIPE
                if (!swiping) {
                    // 밀기가 끝나면 잠금 해제 (삭제 기능이 터치를 가져가서 '손 뗌' 신호가 우리한테 안 옴)
                    horizontalLock = false
                    touching = false
                    list.invalidate()
                    if (refreshPending) scheduleRefresh()
                }
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                horizontalLock = false
                swiping = false
                list.invalidate()
            }
        }).attachToRecyclerView(list)

        content.findViewById<View>(R.id.close).setOnClickListener { clearAllAndClose() }
    }

    /**
     * 고무줄 효과: 맨 위에서 아래로(또는 맨 아래에서 위로) 더 끌면 저항감 있게 따라오고,
     * 손을 떼면 스프링처럼 튕기며 제자리로 돌아온다.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupBounce() {
        var lastY = 0f
        var pull = 0f
        var spring: androidx.dynamicanimation.animation.SpringAnimation? = null
        val maxPull = 160 * density
        // 카드가 터치를 먼저 받아도 손가락 위치/누름 상태는 항상 기록
        // + 처음 움직인 방향으로 축 고정 (좌우로 밀기 시작하면 위아래 스크롤·튕김 없음)
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var decided = false
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastY = e.rawY; spring?.cancel(); touching = true
                        downX = e.rawX; downY = e.rawY
                        decided = false; horizontalLock = false; verticalDecided = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!decided) {
                            val dx = kotlin.math.abs(e.rawX - downX)
                            val dy = kotlin.math.abs(e.rawY - downY)
                            if (dx > slop || dy > slop) {
                                decided = true
                                horizontalLock = dx > dy
                                verticalDecided = !horizontalLock
                                if (verticalDecided) lastY = e.rawY
                            }
                        }
                        if (pull == 0f) lastY = e.rawY
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        if (refreshPending) scheduleRefresh()
                        rv.post { horizontalLock = false }
                    }
                }
                return false
            }
        })
        list.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = e.rawY; spring?.cancel(); touching = true }
                MotionEvent.ACTION_MOVE -> {
                    if (horizontalLock || swiping || !verticalDecided) return@setOnTouchListener false
                    val dy = e.rawY - lastY
                    lastY = e.rawY
                    val atTop = !list.canScrollVertically(-1)
                    val atBottom = !list.canScrollVertically(1)
                    if (pull != 0f || (dy > 0 && atTop) || (dy < 0 && atBottom)) {
                        spring?.cancel()
                        // 많이 끌수록 더 뻑뻑하게
                        val resist = 0.5f * (1f - (kotlin.math.abs(pull) / maxPull)).coerceIn(0.1f, 1f)
                        val next = pull + dy * resist
                        // 방향이 바뀌어 0을 지나면 일반 스크롤로 복귀
                        pull = if (pull != 0f && kotlin.math.sign(next) != kotlin.math.sign(pull)) 0f else next
                        list.translationY = pull
                        if (pull != 0f) return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    if (refreshPending) scheduleRefresh()
                    if (pull != 0f) {
                        pull = 0f
                        spring = androidx.dynamicanimation.animation.SpringAnimation(
                            list, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f
                        ).apply {
                            this.spring.stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
                            this.spring.dampingRatio = 0.55f      // 살짝 튕김
                            start()
                        }
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /** X: 지울 수 있는 알림을 카드가 옆으로 빠지는 애니메이션과 함께 지우고 패널 닫기 */
    private fun clearAllAndClose() {
        val clearable = NotificationStore.items.filter { it.isClearable }
        NotificationStore.removeListener(storeListener)   // 지우는 동안 목록 깜빡임 방지
        var delay = 0L
        for (i in list.childCount - 1 downTo 0) {
            val c = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(c)
            val g = adapter.groups.getOrNull(pos) ?: continue
            if (g.items.none { it.isClearable }) continue
            c.animate().translationXBy(list.width.toFloat()).alpha(0f)
                .setStartDelay(delay).setDuration(200).withLayer().start()
            delay += 40
        }
        NotificationStore.dismissMany(clearable)
        val remain = NotificationStore.items.size - clearable.size
        if (clearable.isEmpty() && remain > 0) {
            Toast.makeText(ctx, "남은 알림은 앱이 고정한 알림이라 지울 수 없어요", Toast.LENGTH_SHORT).show()
        }
        main.postDelayed({ closeAnimated("X 버튼") }, delay + 180)
    }

    /**
     * 카드 배치:
     * - 첫 번째, 두 번째 카드는 절대 위로 끌어올리지 않음 (겹침 금지)
     * - 세 번째부터는 두 번째 카드 아래에 작아지며 겹겹이 쌓이고, 뒤로 갈수록 블러
     * - 쌓인 카드는 X 버튼 영역을 침범하지 않음
     */
    private fun applyStack() {
        val step = STACK_STEP_DP * density
        val floor = list.height - dp(8)                          // 쌓인 카드는 X 밑까지
        var secondBottom = -1f
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (STACK_FROM != Int.MAX_VALUE && list.getChildAdapterPosition(c) == STACK_FROM - 1) secondBottom = c.bottom.toFloat()
        }
        // 쌓이기 시작하는 선: 두 번째 카드 아래
        val limit = maxOf(secondBottom, floor - step * 3)
        val xTop = list.height - dp(12 + 36 + 4)                // X 버튼 윗선
        // 맨 아래에 가까워질수록 쌓기 효과를 풀어서, 끝에서는 원래 간격(10dp) 그대로
        val toBottom = (list.computeVerticalScrollRange() - list.computeVerticalScrollOffset() -
            list.computeVerticalScrollExtent()).toFloat()
        // 카드가 실제로 패널 아래로 넘칠 때만 쌓기 (공간이 남으면 모두 제 간격으로)
        var overflow = false
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (list.getChildAdapterPosition(c) >= STACK_FROM && c.bottom > floor) { overflow = true; break }
        }
        val stackW = if (!overflow) 0f else (toBottom / (90 * density)).coerceIn(0f, 1f)

        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(c)
            val h = c.height.coerceAtLeast(1).toFloat()
            var stackedBehind = false
            c.pivotX = c.width / 2f
            c.pivotY = c.height.toFloat()
            // X 버튼 줄(세 번째 카드 아래 칸)에서만 쌓기 (최대 3장):
            // 네 번째 카드가 맨 위·맨 앞(아래를 둥글게 잘라 짧게), 뒤 카드는 아래로 한 칸씩 내려가며 좁아짐
            val sLine = (list.height - list.paddingBottom).toFloat()
            val peek = 7 * density
            val slot = (c.height + dp(10)).coerceAtLeast(1).toFloat()
            // 겹치기 꺼져 있으면 모든 카드가 제 간격 그대로
            val d = if (stackOn) (c.top - sLine) / slot else -9f
            val dd = d.coerceIn(0f, 2f)
            // 이 카드가 보일 수 있는 아래 한계 (뒤 카드가 삐져나올 자리만큼 위에서 자름)
            val hasNext = pos + 1 < adapter.itemCount
            if (d > -1f && hasNext || d > 0f) {
                val clipBottom = list.height - (2f - dd) * peek
                val visTop = if (d > 0f) sLine + dd * peek else c.top.toFloat()
                setClipHeight(c, (clipBottom - visTop).toInt())
            } else setClipHeight(c, -1)
            if (d > 0f) {
                c.pivotX = c.width / 2f
                c.pivotY = 0f
                c.translationY = (sLine + dd * peek) - c.top
                c.scaleX = 1f - 0.06f * dd
                c.scaleY = 1f
                c.alpha = if (d <= 2f) 1f else (3f - d).coerceIn(0f, 1f)
                c.translationZ = -d
                stackedBehind = d >= 0.5f
                // 뒤로 들어갈수록 카드 속 글씨·아이콘을 지움 → 유리판 띠만
                setContentAlpha(c, (1f - d).coerceIn(0f, 1f))
            } else if (c.top < 0) {
                setContentAlpha(c, 1f)
                val p = (-c.top / h).coerceIn(0f, 1f)
                c.translationY = 0f
                c.scaleX = 1f - 0.06f * p; c.scaleY = c.scaleX
                c.alpha = 1f - p
                c.translationZ = 0f
            } else {
                setContentAlpha(c, 1f)
                c.translationY = 0f
                c.scaleX = 1f; c.scaleY = 1f
                c.alpha = 1f
                c.translationZ = 0f
            }
            val (off, a) = enterOffset(i)
            c.translationY += off
            c.alpha *= a

            // 목록이 바뀐 직후: 예전 자리에서 새 자리로 미끄러지기
            val id = list.getChildItemId(c)
            pendingOld?.let { old ->
                val now = c.top + c.translationY
                moveDelta[id] = (old[id] ?: (now + 40 * density)) - now
                if (old[id] == null) newIds += id
            }
            val dlt = moveDelta[id]
            if (dlt != null && moveT < 1f) {
                val k = 1f - moveInterp.getInterpolation(moveT)
                c.translationY += dlt * k
                if (id in newIds) c.alpha *= 1f - k
            }

            // X 버튼과 겹치는 부분부터 아래로 점점 진해지는 블러
            // (첫 카드 제외, 카드의 둥근 모양 안에서만, 겹치지 않는 윗부분은 선명)
            if (stackedBehind) {
                ProgressiveBlur.apply(c, -2f, -1f, 16 * density)     // 카드 전체 블러
            } else if (pos >= 1) {
                val s = c.scaleY.coerceAtLeast(0.5f)
                val ty = c.translationY
                val hh = c.height.toFloat()
                // 목록 좌표의 높이 Y → 카드 안쪽 좌표 (아래 기준으로 축소된 것 반영)
                fun local(yInList: Float) = hh - (c.top + ty + hh - yInList) / s
                val xTopHere = xTop - list.translationY      // 목록이 튕기며 움직여도 X 기준 유지
                val fade = 30 * density
                val visBottom = c.top + ty + hh
                if (visBottom > xTopHere - fade) {
                    ProgressiveBlur.apply(c, local(xTopHere - fade), local(xTopHere + 10 * density), 14 * density)
                } else ProgressiveBlur.clear(c)
            } else ProgressiveBlur.clear(c)
        }
        if (pendingOld != null) {
            pendingOld = null
            startMove()
        }
    }

    /** 펼친 카드가 X 버튼 위로 전부 보이도록 목록을 부드럽게 스크롤 (다른 카드는 위로 밀려남) */
    private fun revealCard(pos: Int) {
        list.postDelayed({
            val c = list.findViewHolderForAdapterPosition(pos)?.itemView ?: run {
                list.smoothScrollToPosition(pos); return@postDelayed
            }
            val limit = list.height - dp(12 + 36 + 10)     // X 버튼 윗선
            val over = c.bottom - limit
            when {
                c.top < 0 -> list.smoothScrollBy(0, c.top)
                over > 0 -> list.smoothScrollBy(0, minOf(over, c.top - dp(8)).coerceAtLeast(0))
            }
        }, 380)
    }

    // ───────── 목록 변경 애니메이션 ─────────
    private var pendingOld: Map<Long, Float>? = null
    private val moveDelta = HashMap<Long, Float>()
    private val newIds = HashSet<Long>()
    private var moveT = 1f
    private val moveInterp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var moveAnim: ValueAnimator? = null

    /** 지금 카드 위치를 기억하고 목록을 바꾼 뒤, 다음 화면에서 예전 위치→새 위치로 미끄러지게 */
    private fun animateChange(allowShrink: Boolean = false, change: () -> Unit) {
        val old = HashMap<Long, Float>()
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            old[list.getChildItemId(c)] = c.top + c.translationY
        }
        moveDelta.clear(); newIds.clear()
        pendingOld = old
        change()
        refitAfterLayout(allowShrink)
    }

    /** 목록이 바뀐 뒤 최종 배치 확정: 남아 있던 잘림·쌓임 흔적을 지우고 다시 계산 */
    private fun settle() {
        if (closing) return
        moveDelta.clear(); newIds.clear()
        content.requestLayout()
        list.requestLayout()
        list.post {
            for (i in 0 until list.childCount) resetCard(list.getChildAt(i))
            applyStack()
            list.invalidate()
            if (!keyboardWatch) {
                val oldTop = top
                fitHeight(onlyIfChangedBy = dp(6))
                val shift = (oldTop - top).toFloat()
                if (shift != 0f) {
                    list.translationY += shift
                    ValueAnimator.ofFloat(list.translationY, 0f).apply {
                        duration = 300; interpolator = moveInterp
                        addUpdateListener { list.translationY = it.animatedValue as Float }
                        start()
                    }
                }
            }
        }
    }

    /** 카드에 걸어둔 변형·잘림·블러를 원래대로 */
    private fun resetCard(c: View) {
        c.translationY = 0f; c.translationZ = 0f
        c.scaleX = 1f; c.scaleY = 1f; c.alpha = 1f
        setContentAlpha(c, 1f)
        if ((c.getTag(R.id.clip_h) as? Int ?: -1) != -1) { c.setTag(R.id.clip_h, -1); c.invalidateOutline() }
    }

    private fun startMove() {
        moveAnim?.cancel()
        moveT = 0f
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { moveT = it.animatedValue as Float; list.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) = settle()
            })
            start()
        }
    }

    /** 맨 위 알림이 바뀌면 패널 높이 다시 맞추기 (창 크기는 한 번만, 내용은 스프링으로 따라감) */
    private fun refitAfterLayout(allowShrink: Boolean = false) {
        list.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                list.post {
                    val oldTop = top
                    fitHeight(onlyIfChangedBy = dp(6), growOnly = !allowShrink)
                    val shift = (oldTop - top).toFloat()   // 위로 커지면 +
                    // 커졌는데 목록이 살짝 내려가 있으면(맨 위 카드 윗부분 잘림) 맨 위로 맞춤
                    if (shift > 0f && list.computeVerticalScrollOffset() in 1..dp(60)) {
                        list.smoothScrollBy(0, -list.computeVerticalScrollOffset())
                    }
                    if (shift != 0f) {
                        // 창이 위로 커진 만큼 내용을 잠깐 아래로 밀어뒀다가, 카드 이동과 같은 시간·곡선으로 올림
                        // → 아래쪽 카드는 제자리, 펼친 카드와 그 위 카드들이 위로 자라나는 것처럼 보임
                        list.translationY += shift
                        val from = list.translationY
                        ValueAnimator.ofFloat(from, 0f).apply {
                            duration = 300
                            interpolator = moveInterp
                            addUpdateListener { list.translationY = it.animatedValue as Float }
                            start()
                        }
                    }
                }
            }
        })
    }

    /** 카드 아래를 둥글게 잘라 h 높이만 보이게 (h<0 이면 원래대로) */
    private val clipOutline = object : android.view.ViewOutlineProvider() {
        override fun getOutline(v: View, o: android.graphics.Outline) {
            val ch = (v.getTag(R.id.clip_h) as? Int) ?: -1
            val h = if (ch in 1 until v.height) ch else v.height
            val r = minOf(26 * density, h / 2f)
            o.setRoundRect(0, 0, v.width, h, r)
        }
    }

    private fun setClipHeight(c: View, h: Int) {
        val nh = if (h <= 0 || h >= c.height) -1 else maxOf(h, dp(14))
        if (c.outlineProvider !== clipOutline) {
            c.outlineProvider = clipOutline
            c.clipToOutline = true
        }
        if ((c.getTag(R.id.clip_h) as? Int ?: -1) != nh) {
            c.setTag(R.id.clip_h, nh)
            c.invalidateOutline()
        }
    }

    /** 카드 배경(유리판)은 두고 안쪽 내용만 투명도 조절 */
    private fun setContentAlpha(c: View, a: Float) {
        val g = c as? android.view.ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v.alpha != a) v.alpha = a
        }
    }

    /** 카드 블러 (값이 바뀔 때만 다시 설정해서 부담 줄임) */
    private fun setBlur(v: View, r: Int) {
        if (Build.VERSION.SDK_INT < 31) return
        val bucket = (r / 2) * 2
        if ((v.getTag(R.id.list) as? Int ?: 0) == bucket) return
        v.setTag(R.id.list, bucket)
        v.setRenderEffect(
            if (bucket <= 0) null
            else RenderEffect.createBlurEffect(bucket * density / 2, bucket * density / 2, Shader.TileMode.DECAL)
        )
    }

    // 열릴 때 카드들이 아래에서 차례로 떠오르기
    private var enterT = 1f
    private val enterInterp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun enterOffset(index: Int): Pair<Float, Float> {
        if (enterT >= 1f) return 0f to 1f
        val ms = enterT * 600f - 40f - index * 40f
        val p = enterInterp.getInterpolation((ms / 380f).coerceIn(0f, 1f))
        return (1f - p) * 50 * density to p
    }

    private fun staggerIn() {
        enterT = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            addUpdateListener { enterT = it.animatedValue as Float; list.invalidate() }
            start()
        }
    }

    // 알림이 자주 바뀌어도(녹화·만보기 등) 0.4초에 한 번, 내용이 바뀐 경우에만 다시 그림
    private var lastSig = ""
    private val refreshRunnable = Runnable { refreshNow() }

    private var swiping = false
    private var horizontalLock = false     // 좌우로 미는 중 → 세로 스크롤 잠금
    private var verticalDecided = false    // 위아래로 움직이기로 확정됨 → 튕김 허용
    private var touching = false
    private var refreshPending = false

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        // 밀거나 끄는 중에는 목록을 건드리지 않고 손 뗀 뒤에 반영
        if (swiping || touching || keyboardWatch) { refreshPending = true; return }
        refreshPending = false
        main.postDelayed(refreshRunnable, 250)
    }

    private fun refreshNow() {
        if (!NotificationStore.connected) {
            adapter.submit(emptyList())
            emptyView.text = "설정에서 '알림 접근 허용'을 켜 주세요"
            emptyView.visibility = View.VISIBLE
            return
        }
        val items = NotificationStore.items
        val sig = items.joinToString("|") {
            val ex = it.notification.extras
            "${it.key}:${ex.getCharSequence(Notification.EXTRA_TITLE)}:${ex.getCharSequence(Notification.EXTRA_TEXT)}"
        }
        if (sig == lastSig && adapter.itemCount > 0) return
        val first = lastSig.isEmpty()
        lastSig = sig
        if (first) adapter.submit(NotiAdapter.group(items))
        else animateChange { adapter.submit(NotiAdapter.group(items)) }
        emptyView.text = "알림이 없어요"
        emptyView.visibility = if (adapter.groups.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendPending(pi: android.app.PendingIntent?, fill: android.content.Intent? = null) {
        pi ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                pi.send(ctx, 0, fill, null, null, null, opts.toBundle())
            } else {
                pi.send(ctx, 0, fill)
            }
        }
    }

    /** 바로 답장 보내기 (카톡·문자·텔레그램 등) */
    private fun sendReply(a: Notification.Action, text: CharSequence) {
        val inputs = a.remoteInputs ?: return
        val fill = android.content.Intent()
        val results = android.os.Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
        sendPending(a.actionIntent, fill)
        Toast.makeText(ctx, "보냈어요", Toast.LENGTH_SHORT).show()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        window?.decorView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        dropFocus()
    }

    private fun openNotification(sbn: StatusBarNotification) {
        sendPending(sbn.notification.contentIntent)
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            NotificationStore.dismiss(sbn)
        }
        closeAnimated("알림 열기")
    }

    companion object {
        const val STACK_STEP_DP = 8
        /** 카드 쌓기 끔: 모든 카드가 제 간격대로 (넘치는 카드는 X 뒤로 블러되며 이어짐) */
        const val STACK_FROM = Int.MAX_VALUE
        /** 마지막으로 잰 키보드 높이 (다음에 키보드와 동시에 움직일 때 사용) */
        var cachedImeHeight = 0
    }
}
