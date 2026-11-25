package com.leseraphim.notipanel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Dialog
import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 블러 배경 알림 패널.
 * 터치 아이콘 위치에서 시작해 왼쪽 위 방향으로 창 크기를 키우며 펼쳐진다.
 */
class PanelDialog(
    private val ctx: Context,
    private val from: Rect,   // 시작 영역(터치 아이콘)
    private val to: Rect,     // 최종 패널 영역
    private val blurRadius: Int
) : Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar) {

    private val density = ctx.resources.displayMetrics.density
    private val corner = 28 * density
    private lateinit var content: View
    private lateinit var adapter: NotiAdapter
    private lateinit var emptyView: TextView
    private var animator: ValueAnimator? = null
    private var closing = false

    private val storeListener: () -> Unit = { refresh() }

    init {
        val root = FrameLayout(ctx)
        // 내용물은 최종 크기로 고정하고 오른쪽 아래에 붙여둔다
        // → 창이 왼쪽 위로 커지면서 내용이 드러나는 효과
        content = LayoutInflater.from(ctx).inflate(R.layout.panel, root, false)
        root.addView(
            content,
            FrameLayout.LayoutParams(to.width(), to.height(), Gravity.BOTTOM or Gravity.END)
        )
        root.clipChildren = true
        setContentView(root)
        setupList()
        setupWindow()
    }

    private fun setupWindow() {
        val w = window ?: return
        // 접근성 서비스에서 띄우면 키보드보다 위에 뜨는 창 사용
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
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        val bg = GradientDrawable().apply { cornerRadius = corner }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled && blurRadius > 0
        if (blurOk) {
            bg.setColor(Color.argb(70, 60, 60, 60))     // 블러 위에 살짝 회색 막 (사진 느낌)
            w.setBackgroundDrawable(bg)
            w.setBackgroundBlurRadius(blurRadius)
        } else {
            bg.setColor(Color.argb(235, 80, 80, 80))   // 블러 미지원 시 반투명 배경
            w.setBackgroundDrawable(bg)
        }
        w.decorView.setPadding(0, 0, 0, 0)
        applyRect(0f)
    }

    // ───────── 애니메이션 ─────────
    private fun lerp(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt()

    private fun applyRect(t: Float) {
        val w = window ?: return
        val lp = w.attributes
        lp.x = lerp(from.left, to.left, t)
        lp.y = lerp(from.top, to.top, t)
        lp.width = maxOf(1, lerp(from.right, to.right, t) - lp.x)
        lp.height = maxOf(1, lerp(from.bottom, to.bottom, t) - lp.y)
        w.attributes = lp
        content.alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
    }

    private fun animate(fromT: Float, toT: Float, duration: Long, end: () -> Unit = {}) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(fromT, toT).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { applyRect(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = end()
            })
            start()
        }
    }

    fun showAnimated() {
        show()
        animate(0f, 1f, 380)
        staggerIn()
    }

    /**
     * 닫기: 창을 잘라내며 줄이지 않고,
     * 패널 전체가 아이콘 쪽(오른쪽 아래)으로 살짝 작아지면서 블러와 함께 사라진다.
     */
    fun closeAnimated() {
        if (closing) return
        closing = true
        animator?.cancel()
        val w = window ?: return dismissNow()
        val lp = w.attributes
        val baseX = lp.x; val baseY = lp.y
        val baseW = lp.width; val baseH = lp.height
        val right = baseX + baseW
        val bottom = baseY + baseH
        val startAlpha = content.alpha
        val blurOk = Build.VERSION.SDK_INT >= 31 && blurRadius > 0

        content.pivotX = content.width.toFloat()
        content.pivotY = content.height.toFloat()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = PathInterpolator(0.4f, 0f, 0.9f, 0.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val f = 1f - 0.14f * t                       // 14%까지 작아짐
                val a = lp
                a.width = maxOf(1, (baseW * f).toInt())
                a.height = maxOf(1, (baseH * f).toInt())
                a.x = right - a.width                          // 오른쪽 아래 고정
                a.y = bottom - a.height
                a.alpha = 1f - t                               // 창(블러 포함) 서서히 투명
                w.attributes = a
                if (blurOk) w.setBackgroundBlurRadius((blurRadius * (1f - t)).toInt())
                content.scaleX = f
                content.scaleY = f
                content.alpha = (startAlpha * (1f - t * 1.5f)).coerceIn(0f, 1f)
                content.translationY = 24 * density * t       // 살짝 아래로 가라앉음
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = dismissNow()
            })
            start()
        }
    }

    fun dismissNow() {
        animator?.cancel()
        if (isShowing) runCatching { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        NotificationStore.addListener(storeListener)
        refresh()
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

    // ───────── 알림 목록 ─────────
    private lateinit var list: RecyclerView

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

        // 스크롤할 때마다 카드 쌓임 효과 다시 계산
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = applyStack()
        })
        list.viewTreeObserver.addOnPreDrawListener { applyStack(); true }

        // 좌우로 밀어서 알림 지우기 (그룹째)
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder
            ) = false

            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val g = adapter.groups.getOrNull(vh.bindingAdapterPosition) ?: return 0
                return if (g.items.any { it.isClearable }) super.getSwipeDirs(rv, vh) else 0
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val g = adapter.groups.getOrNull(vh.bindingAdapterPosition) ?: return
                g.items.filter { it.isClearable }.forEach { NotificationStore.dismiss(it) }
            }
        }).attachToRecyclerView(list)

        content.findViewById<View>(R.id.close).setOnClickListener { closeAnimated() }
    }

    /**
     * 영상처럼: 아래쪽으로 넘친 카드는 작아지면서 뒤로 겹겹이 쌓이고,
     * 위로 밀려 올라간 카드는 살짝 작아지며 흐려진다.
     */
    private fun applyStack() {
        val limit = list.height - list.paddingBottom + (10 * density)   // 쌓이기 시작하는 선
        val step = 9 * density                                           // 층 간격
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            val h = c.height.coerceAtLeast(1).toFloat()
            val over = c.bottom - limit
            if (over > 0) {
                val depth = over / h
                val d = depth.coerceAtMost(3f)
                c.pivotX = c.width / 2f
                c.pivotY = c.height.toFloat()
                c.translationY = -over + d * step
                c.scaleX = 1f - 0.05f * d
                c.scaleY = c.scaleX
                c.alpha = (1f - (depth - 2f).coerceAtLeast(0f)).coerceIn(0f, 1f)
                c.translationZ = -depth
            } else if (c.top < 0) {
                val p = (-c.top / h).coerceIn(0f, 1f)
                c.pivotX = c.width / 2f
                c.pivotY = c.height.toFloat()
                c.translationY = 0f
                c.scaleX = 1f - 0.08f * p
                c.scaleY = c.scaleX
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
        }
    }

    /** 열릴 때 카드들이 아래에서 차례로 떠오르기 (0~1) */
    private var enterT = 1f
    private val enterInterp = PathInterpolator(0.2f, 0f, 0f, 1f)

    private fun enterOffset(index: Int): Pair<Float, Float> {
        if (enterT >= 1f) return 0f to 1f
        val ms = enterT * 700f - 60f - index * 40f
        val p = enterInterp.getInterpolation((ms / 420f).coerceIn(0f, 1f))
        return (1f - p) * 70 * density to p
    }

    private fun staggerIn() {
        enterT = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener { enterT = it.animatedValue as Float; list.invalidate() }
            start()
        }
    }

    private fun refresh() {
        if (!NotificationStore.connected) {
            adapter.submit(emptyList())
            emptyView.text = "설정에서 '알림 접근 허용'을 켜 주세요"
            emptyView.visibility = View.VISIBLE
            return
        }
        adapter.submit(NotiAdapter.group(NotificationStore.items))
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

    /** 답장 보내기 (카톡 등 '답장' 버튼) */
    private fun sendReply(a: Notification.Action, text: CharSequence) {
        val inputs = a.remoteInputs ?: return
        val fill = android.content.Intent()
        val results = android.os.Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
        sendPending(a.actionIntent, fill)
    }

    private fun openNotification(sbn: StatusBarNotification) {
        sendPending(sbn.notification.contentIntent)
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            NotificationStore.dismiss(sbn)
        }
        closeAnimated()
    }
}
