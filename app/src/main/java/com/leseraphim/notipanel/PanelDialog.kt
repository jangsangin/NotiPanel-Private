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
        bottom = (screenH * 0.72f).toInt()
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

    private var imeShift = 0
    private fun moveAboveKeyboard(ime: Int) {
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
    private fun fitHeight() {
        val gap = dp(10)
        val first = list.getChildAt(0)
        val second = list.getChildAt(1)
        // 뒤에 쌓이는 카드 수만큼만 여유를 둠 (X 버튼과 간격 최소화)
        val stacked = (adapter.itemCount - 2).coerceIn(0, 3)
        val need = if (first == null) dp(200) else
            list.paddingTop + first.height + gap +
                (second?.let { it.height + gap } ?: 0) +
                (if (stacked > 0) dp(stacked * 9) else 0) +
                list.paddingBottom
        val h = need.coerceIn(dp(180), maxHeight())
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
    fun closeAnimated() {
        if (closing) return
        closing = true
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
                closeAnimated()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = closeAnimated()

    // ───────── 목록 ─────────
    private fun setupList() {
        list = content.findViewById(R.id.list)
        emptyView = content.findViewById(R.id.empty)
        adapter = NotiAdapter(
            ctx,
            onOpen = { openNotification(it) },
            onAction = { a -> sendPending(a.actionIntent); closeAnimated() },
            onReply = { a, text -> sendReply(a, text) }
        )
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        list.setHasFixedSize(false)
        list.viewTreeObserver.addOnPreDrawListener { applyStack(); true }

        // 좌우로 밀어서 알림 지우기
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val g = adapter.groups.getOrNull(vh.bindingAdapterPosition) ?: return 0
                return if (g.items.any { it.isClearable }) super.getSwipeDirs(rv, vh) else 0
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val g = adapter.groups.getOrNull(vh.bindingAdapterPosition) ?: return
                NotificationStore.dismissMany(g.items.filter { it.isClearable })
            }
        }).attachToRecyclerView(list)

        content.findViewById<View>(R.id.close).setOnClickListener { clearAllAndClose() }
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
        main.postDelayed({ closeAnimated() }, delay + 180)
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
        // 쌓이기 시작하는 선: 두 번째 카드 아래, 그리고 X 위 (쌓임 여유만큼)
        val limit = maxOf(secondBottom, floor - step * 3)

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
                c.translationY = (limit + dd * step) - c.bottom
                c.scaleX = 1f - 0.05f * dd
                c.scaleY = c.scaleX
                c.alpha = (1f - (depth - 2f).coerceAtLeast(0f)).coerceIn(0f, 1f) * 0.95f
                c.translationZ = -depth
                setBlur(c, (dd * 6).toInt().coerceIn(4, 18))
            } else if (c.top < 0) {
                val p = (-c.top / h).coerceIn(0f, 1f)
                c.translationY = 0f
                c.scaleX = 1f - 0.06f * p; c.scaleY = c.scaleX
                c.alpha = 1f - p
                c.translationZ = 0f
                setBlur(c, 0)
            } else {
                c.translationY = 0f
                c.scaleX = 1f; c.scaleY = 1f
                c.alpha = 1f
                c.translationZ = 0f
                setBlur(c, 0)
            }
            val (off, a) = enterOffset(i)
            c.translationY += off
            c.alpha *= a
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

    private fun scheduleRefresh() {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 400)
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
        lastSig = sig
        adapter.submit(NotiAdapter.group(items))
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
        closeAnimated()
    }

    companion object {
        const val STACK_STEP_DP = 8
    }
}
