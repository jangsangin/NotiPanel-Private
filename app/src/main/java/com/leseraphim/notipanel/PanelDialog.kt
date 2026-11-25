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
    private val blurRadius: Int
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
        blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled && blurRadius > 0

        val root = FrameLayout(ctx)
        content = LayoutInflater.from(ctx).inflate(R.layout.panel, root, false)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        setupList()
        setupWindow()
    }

    private fun maxHeight() = (screenH * 0.56f).toInt()

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

        val bg = GradientDrawable().apply { cornerRadius = corner }
        if (blurOk) {
            bg.setColor(Color.argb(70, 60, 60, 60))
            w.setBackgroundDrawable(bg)
            w.setBackgroundBlurRadius(0)
        } else {
            bg.setColor(Color.argb(235, 80, 80, 80))
            w.setBackgroundDrawable(bg)
        }
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
    }

    // ───────── 키보드 따라 패널 올리기 ─────────
    private var keyboardWatch = false
    private var lastImeTop: Int? = null
    private var posAnim: ValueAnimator? = null

    /** 입력칸이 열리면 호출: 키보드 위치를 계속 확인해서 패널을 그 위로 */
    fun watchKeyboard(on: Boolean) {
        keyboardWatch = on
        if (on) {
            main.removeCallbacks(keyboardTick)
            main.post(keyboardTick)
        } else {
            main.removeCallbacks(keyboardTick)
            lastImeTop = null
            slideWindowTo(baseY)
        }
    }

    private val keyboardTick = object : Runnable {
        override fun run() {
            if (!keyboardWatch || closing) return
            val a11y = ctx as? A11yService ?: A11yService.instance
            val imeTop = a11y?.imeTop()
                ?: (screenH * 0.58f).toInt()                 // 못 읽으면 키보드 높이 추정
            if (imeTop != lastImeTop) {
                lastImeTop = imeTop
                val h = window?.attributes?.height ?: 0
                val want = (imeTop - dp(8) - h).coerceAtLeast(dp(30))
                slideWindowTo(minOf(baseY, want))
            }
            main.postDelayed(this, 120)
        }
    }

    /** 창 위치만(크기는 그대로) 짧게 미끄러뜨림 → 크기 변경이 아니라 가벼움 */
    private fun slideWindowTo(y: Int) {
        val w = window ?: return
        val from = w.attributes.y
        if (from == y) return
        posAnim?.cancel()
        posAnim = ValueAnimator.ofInt(from, y).apply {
            duration = 220
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val lp = w.attributes
                lp.y = it.animatedValue as Int
                w.attributes = lp
            }
            start()
        }
    }

    private var imeShift = 0
    private var lastIme = 0
    private fun moveAboveKeyboard(ime: Int) {
        if (lastIme > 0 && ime == 0) currentFocus?.clearFocus()
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
    private fun fitHeight(onlyIfChangedBy: Int = 0) {
        val gap = dp(10)
        val first = list.getChildAt(0)
        val second = list.getChildAt(1)
        // 뒤에 쌓이는 카드 수만큼만 여유를 둠 (X 버튼과 간격 최소화)
        val stacked = (adapter.itemCount - 2).coerceIn(0, 3)
        // 첫 알림이 답장 가능한 채팅이면: 두 번째 알림은 일부만 보이고 X가 그 30% 정도를 덮음
        val replyMode = adapter.groups.firstOrNull()?.latest?.notification?.actions
            ?.any { !it.remoteInputs.isNullOrEmpty() } == true
        val xZone = dp(12 + 36)                    // X 버튼 + 아래 여백
        val need = if (first == null) dp(200)
        else if (replyMode)
            list.paddingTop + first.height + gap +
                (second?.let { (it.height * 0.45f).toInt() + (xZone * 0.7f).toInt() } ?: xZone) +
                list.paddingBottom
        else
            // 상태바 알림창처럼: 두 번째 카드 아래 쌓인 카드들 위로 X가 떠 있음
            list.paddingTop + first.height + gap +
                (second?.let { it.height + gap } ?: 0) +
                (if (stacked > 0) dp(stacked * 9) else 0) +
                dp(20) + list.paddingBottom
        val h = need.coerceIn(dp(180), maxHeight())
        if (onlyIfChangedBy > 0 && kotlin.math.abs(h - (bottom - top)) < onlyIfChangedBy) return
        top = bottom - h
        placeWindow(top, h)
    }

    private fun animateOpen() {
        val d = window?.decorView ?: return
        val h = bottom - top
        // 사이드바 쪽(오른쪽, 사이드바 높이)에서 왼쪽 위로 펼쳐짐
        d.pivotX = (right - left).toFloat()
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
        d.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).translationY(dp(16).toFloat())
            .setDuration(220).setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withLayer()
            .withEndAction { dismissNow() }
            .start()
        val cur = if (blurOk) blurRadius else 0
        animateBlur(cur, 0, 200)
    }

    fun dismissNow() {
        blurAnim?.cancel()
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val d = window?.decorView ?: return false
            val x = event.x.toInt()
            val y = event.y.toInt()
            if (x < 0 || y < 0 || x > d.width || y > d.height) {
                if (keyboardWatch) { currentFocus?.clearFocus(); return true }   // 답장 중이면 입력만 닫기
                closeAnimated("패널 바깥 터치")
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 뒤로가기: 답장 입력 중이면 입력만 닫고 패널은 유지 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (keyboardWatch || currentFocus is android.widget.EditText) {
            currentFocus?.clearFocus()
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
            onReplyOpen = { open -> watchKeyboard(open) }
        )
        list.layoutManager = object : LinearLayoutManager(ctx) {
            override fun canScrollVertically() = !horizontalLock && super.canScrollVertically()
        }
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(false)
        // 좌우로 미는 동안에는 다른 카드 위치·크기를 그대로 유지 (재계산 안 함)
        list.viewTreeObserver.addOnPreDrawListener {
            if (!horizontalLock && !swiping) applyStack()
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
                animateChange { adapter.removeAt(pos) }
                NotificationStore.dismissMany(g.items.filter { it.isClearable })
            }

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                swiping = state == ItemTouchHelper.ACTION_STATE_SWIPE
                if (!swiping && refreshPending) scheduleRefresh()
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
        val floor = list.height - list.paddingBottom            // 이 아래로는 못 내려감 (X 버튼 영역)
        var secondBottom = -1f
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (list.getChildAdapterPosition(c) == 1) secondBottom = c.bottom.toFloat()
        }
        // 쌓이기 시작하는 선: 두 번째 카드 아래
        val limit = maxOf(secondBottom, floor - step * 3)
        val xTop = list.height - dp(12 + 36 + 4)                // X 버튼 윗선
        // 맨 아래에 가까워질수록 쌓기 효과를 풀어서, 끝에서는 원래 간격(10dp) 그대로
        val toBottom = (list.computeVerticalScrollRange() - list.computeVerticalScrollOffset() -
            list.computeVerticalScrollExtent()).toFloat()
        val stackW = (toBottom / (90 * density)).coerceIn(0f, 1f)

        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val pos = list.getChildAdapterPosition(c)
            val h = c.height.coerceAtLeast(1).toFloat()
            c.pivotX = c.width / 2f
            c.pivotY = c.height.toFloat()
            if (pos >= 2 && c.bottom > limit) {
                val depth = ((c.bottom - limit) / h).coerceAtLeast(0.05f)
                val dd = depth.coerceAtMost(3f)
                // 바닥을 limit + 층간격 에 맞춤 (위로 올라가더라도 두 번째 카드 아래까지만)
                // stackW: 맨 아래로 갈수록 0 → 원래 자리·크기로 돌아옴
                c.translationY = ((limit + dd * step) - c.bottom) * stackW
                c.scaleX = 1f - 0.05f * dd * stackW
                c.scaleY = c.scaleX
                val a0 = (1f - (depth - 2f).coerceAtLeast(0f)).coerceIn(0f, 1f) * 0.95f
                c.alpha = 1f + (a0 - 1f) * stackW
                c.translationZ = -depth * stackW
                // (블러는 아래에서 X 기준 그라데이션으로)
            } else if (c.top < 0) {
                val p = (-c.top / h).coerceIn(0f, 1f)
                c.translationY = 0f
                c.scaleX = 1f - 0.06f * p; c.scaleY = c.scaleX
                c.alpha = 1f - p
                c.translationZ = 0f
            } else {
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
            if (pos >= 1) {
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

    // ───────── 목록 변경 애니메이션 ─────────
    private var pendingOld: Map<Long, Float>? = null
    private val moveDelta = HashMap<Long, Float>()
    private val newIds = HashSet<Long>()
    private var moveT = 1f
    private val moveInterp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var moveAnim: ValueAnimator? = null

    /** 지금 카드 위치를 기억하고 목록을 바꾼 뒤, 다음 화면에서 예전 위치→새 위치로 미끄러지게 */
    private fun animateChange(change: () -> Unit) {
        val old = HashMap<Long, Float>()
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            old[list.getChildItemId(c)] = c.top + c.translationY
        }
        moveDelta.clear(); newIds.clear()
        pendingOld = old
        change()
        refitAfterLayout()
    }

    private fun startMove() {
        moveAnim?.cancel()
        moveT = 0f
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            addUpdateListener { moveT = it.animatedValue as Float; list.invalidate() }
            start()
        }
    }

    /** 맨 위 알림이 바뀌면 패널 높이 다시 맞추기 (창 크기는 한 번만, 내용은 스프링으로 따라감) */
    private fun refitAfterLayout() {
        list.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                list.post {
                    val oldTop = top
                    fitHeight(onlyIfChangedBy = dp(6))
                    val shift = (oldTop - top).toFloat()   // 위로 커지면 +
                    if (shift != 0f) {
                        // 창이 바뀐 만큼 내용이 튀지 않게 잠깐 반대로 밀어뒀다가 스프링으로 제자리
                        list.translationY += shift
                        androidx.dynamicanimation.animation.SpringAnimation(
                            list, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f
                        ).apply {
                            this.spring.stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_MEDIUM
                            this.spring.dampingRatio = 0.85f
                            start()
                        }
                    }
                }
            }
        })
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
        if (swiping || touching) { refreshPending = true; return }
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
        currentFocus?.clearFocus()
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
    }
}
