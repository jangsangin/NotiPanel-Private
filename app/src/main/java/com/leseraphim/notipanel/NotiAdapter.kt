package com.leseraphim.notipanel

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 같은 앱 알림 묶음 */
class NotiGroup(val pkg: String, val items: List<StatusBarNotification>) {
    val latest get() = items.first()
}

/**
 * 맨 위 묶음은 내용이 다 보이게 펼치고(사진·버튼·답장),
 * 나머지는 두 줄짜리 카드로 접어서 보여준다.
 */
class NotiAdapter(
    private val ctx: Context,
    private val onOpen: (StatusBarNotification) -> Unit,
    private val onAction: (Notification.Action) -> Unit,
    private val onReply: (Notification.Action, CharSequence) -> Unit,
    private val onReplyOpen: (Boolean) -> Unit = {},
    private val onToggle: (Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EXPANDED = 0
        private const val TYPE_COLLAPSED = 1

        fun group(list: List<StatusBarNotification>): List<NotiGroup> =
            list.groupBy { it.packageName }
                .map { (pkg, l) -> NotiGroup(pkg, l.sortedByDescending { it.postTime }) }
                .sortedByDescending { it.latest.postTime }
    }

    var groups: List<NotiGroup> = emptyList()
        private set

    private val appNames = HashMap<String, String>()

    /** 화살표로 펼친 묶음 (앱 패키지) */
    val expanded = HashSet<String>()
    private val appIcons = HashMap<String, Drawable?>()
    private val density = ctx.resources.displayMetrics.density

    init { setHasStableIds(true) }

    /** 바뀐 카드만 골라서 갱신 (전체 다시 그리기 X → 끊김 방지) */
    fun submit(list: List<NotiGroup>) {
        val old = groups
        groups = list
        if (old.isEmpty() || list.isEmpty()) { notifyDataSetChanged(); return }
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object :
            androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].pkg == list[n].pkg
            override fun areContentsTheSame(o: Int, n: Int) =
                o == n && sig(old[o]) == sig(list[n])     // 위치가 바뀌면(펼침↔접힘) 다시 그림
        }, true)
        diff.dispatchUpdatesTo(this)
        // 맨 위(펼친 카드)가 바뀌었으면 0, 1번 모양 다시 맞추기
        if (old.first().pkg != list.first().pkg) {
            notifyItemChanged(0)
            if (list.size > 1) notifyItemChanged(1)
        }
    }

    private fun sig(g: NotiGroup) = g.items.joinToString(",") {
        val ex = it.notification.extras
        "${it.key}:${it.postTime}:${ex.getCharSequence(Notification.EXTRA_TEXT)}"
    }

    /** 스와이프로 지운 묶음을 바로 목록에서 빼기 (알림 서버 응답 기다리지 않음) */
    fun removeAt(pos: Int) {
        if (pos !in groups.indices) return
        groups = groups.toMutableList().also { it.removeAt(pos) }
        notifyItemRemoved(pos)
        if (pos == 0 && groups.isNotEmpty()) notifyItemChanged(0)
    }

    override fun getItemCount() = groups.size
    override fun getItemId(position: Int) = groups[position].pkg.hashCode().toLong()
    override fun getItemViewType(position: Int) = if (position == 0) TYPE_EXPANDED else TYPE_COLLAPSED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(ctx)
        return if (viewType == TYPE_EXPANDED)
            ExpandedVH(inf.inflate(R.layout.item_expanded, parent, false))
        else
            CollapsedVH(inf.inflate(R.layout.item_collapsed, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val g = groups[position]
        when (holder) {
            is ExpandedVH -> holder.bind(g)
            is CollapsedVH -> holder.bind(g)
        }
    }

    // ───────── 공통 도우미 ─────────
    private fun dp(v: Int) = (v * density).toInt()

    private fun round(v: View) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, o: Outline) = o.setOval(0, 0, view.width, view.height)
        }
        v.clipToOutline = true
    }

    private fun appName(pkg: String) = appNames.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) { pkg }
    }

    private fun appIcon(pkg: String) = appIcons.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun largeIcon(n: Notification): Drawable? =
        runCatching { n.getLargeIcon()?.loadDrawable(ctx) }.getOrNull()

    private fun time(sbn: StatusBarNotification) = DateUtils.getRelativeTimeSpanString(
        sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
    )

    private fun title(n: Notification): CharSequence {
        val ex = n.extras
        return ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE) ?: ""
    }

    private fun text(n: Notification): CharSequence {
        val ex = n.extras
        return ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT) ?: ""
    }

    /** 대화형 알림(카톡 등)의 메시지들: (보낸 사람, 내용) */
    private fun messages(n: Notification): List<Pair<CharSequence?, CharSequence>> {
        val arr: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33)
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        return arr?.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val t = b.getCharSequence("text") ?: return@mapNotNull null
            b.getCharSequence("sender") to t
        } ?: emptyList()
    }

    private fun picture(n: Notification): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 33)
            n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
    }.getOrNull()

    private fun bold(s: CharSequence): CharSequence =
        SpannableStringBuilder(s).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    // ───────── 맨 위 펼친 카드 ─────────
    inner class ExpandedVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val appIconV: ImageView = v.findViewById(R.id.appIcon)
        private val appNameV: TextView = v.findViewById(R.id.appName)
        private val timeV: TextView = v.findViewById(R.id.time)
        private val titleV: TextView = v.findViewById(R.id.title)
        private val messagesV: LinearLayout = v.findViewById(R.id.messages)
        private val pictureV: ImageView = v.findViewById(R.id.picture)
        private val replyBox = ReplyBox(v)

        init {
            round(icon); round(appIconV)
            pictureV.clipToOutline = true
            pictureV.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) =
                    o.setRoundRect(0, 0, view.width, view.height, 12 * density)
            }
        }

        fun bind(g: NotiGroup) {
            val sbn = g.latest
            val n = sbn.notification

            val large = largeIcon(n)
            if (large != null) {
                icon.setImageDrawable(large)
                appIconV.setImageDrawable(appIcon(sbn.packageName))
                appIconV.visibility = View.VISIBLE
            } else {
                icon.setImageDrawable(appIcon(sbn.packageName))
                appIconV.visibility = View.GONE
            }
            appNameV.text = appName(sbn.packageName)
            timeV.text = time(sbn)
            titleV.text = title(n)

            // 메시지 (패널이 너무 커지지 않게: 최근 3개, 각 2줄)
            messagesV.removeAllViews()
            val msgs = messages(n).takeLast(3)
            if (msgs.isNotEmpty()) {
                for ((sender, t) in msgs) {
                    val sb = SpannableStringBuilder()
                    if (sender != null && sender.toString() != title(n).toString()) {
                        sb.append(bold(sender)).append("  ")
                    }
                    sb.append(t)
                    messagesV.addView(line(sb, 14f, 0.95f, maxLines = 2, top = dp(3)))
                }
            } else {
                val t = text(n)
                if (t.isNotEmpty()) messagesV.addView(line(t, 14f, 0.95f, maxLines = 4))
            }
            // 같은 앱의 다른 알림: 펼쳤으면 아래 목록으로, 아니면 2개까지 한 줄씩
            val subs: LinearLayout = itemView.findViewById(R.id.subItems)
            val isExp = g.pkg in expanded && g.items.size >= 2
            if (isExp) { subs.visibility = View.VISIBLE; fillSubs(subs, g.items.drop(1)) }
            else { subs.visibility = View.GONE; subs.removeAllViews() }
            setupExpand(this, g)
            if (!isExp) g.items.drop(1).take(2).forEach {
                val s = SpannableStringBuilder(bold(title(it.notification))).append("  ").append(text(it.notification))
                messagesV.addView(line(s, 13f, 0.7f, maxLines = 1, top = dp(4)))
            }

            val pic = picture(n)
            pictureV.visibility = if (pic != null) View.VISIBLE else View.GONE
            pictureV.setImageBitmap(pic)

            // 버튼 줄: 답장 · 읽음 등 (답장을 누르면 같은 자리에서 입력칸으로 바뀜)
            replyBox.bind(n, maxButtons = 3)

            itemView.setOnClickListener { onOpen(sbn) }
        }

        private fun line(t: CharSequence, size: Float, alpha: Float, maxLines: Int = 4, top: Int = dp(2)) =
            TextView(ctx).apply {
                text = t
                textSize = size
                setTextColor(Color.argb((alpha * 255).toInt(), 255, 255, 255))
                this.maxLines = maxLines
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
            }
    }

    // ───────── 묶음 펼치기 (화살표) ─────────
    /** 화살표 상태 맞추고 누르면 펼침/접힘 */
    private fun setupExpand(holder: RecyclerView.ViewHolder, g: NotiGroup) {
        val btn: ImageView = holder.itemView.findViewById(R.id.expandBtn)
        val many = g.items.size >= 2
        btn.visibility = if (many) View.VISIBLE else View.GONE
        val isExp = g.pkg in expanded
        btn.animate().cancel()
        btn.rotation = if (isExp) 180f else 0f
        btn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (!expanded.remove(g.pkg)) expanded += g.pkg
            btn.animate().rotation(if (g.pkg in expanded) 180f else 0f).setDuration(200).start()
            onToggle(pos)
        }
    }

    /** 펼쳤을 때 묶음 안의 알림을 하나씩 보여줌 (채팅이면 답장 가능) */
    private fun fillSubs(container: LinearLayout, items: List<StatusBarNotification>) {
        container.removeAllViews()
        val inf = LayoutInflater.from(ctx)
        items.forEach { sbn ->
            val n = sbn.notification
            val v = inf.inflate(R.layout.item_sub, container, false)
            v.findViewById<TextView>(R.id.subTitle).text = title(n).ifEmpty { appName(sbn.packageName) }
            v.findViewById<TextView>(R.id.subTime).text = time(sbn)
            val msgs = messages(n).takeLast(2)
            val body: CharSequence = if (msgs.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                msgs.forEachIndexed { i, (sender, t) ->
                    if (i > 0) sb.append("\n")
                    if (sender != null && sender.toString() != title(n).toString()) sb.append(bold(sender)).append("  ")
                    sb.append(t)
                }
                sb
            } else text(n)
            v.findViewById<TextView>(R.id.subText).apply {
                text = body
                visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
            }
            val box = ReplyBox(v)
            val canReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            if (canReply) box.bind(n, maxButtons = 2) else box.hide()
            v.setOnClickListener { onOpen(sbn) }
            container.addView(v)
        }
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(220).start()
    }

    // ───────── 버튼 줄 ↔ 답장 입력칸 (펼친 카드·접힌 카드 공용) ─────────
    inner class ReplyBox(private val card: View) {
        private val area: View = card.findViewById(R.id.actionArea)
        private val actionsV: LinearLayout = card.findViewById(R.id.actions)
        private val replyRow: View = card.findViewById(R.id.replyRow)
        private val replyInput: EditText = card.findViewById(R.id.replyInput)
        private val replySend: View = card.findViewById(R.id.replySend)
        private val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        private var replyOpen = false

        fun hide() {
            reset()
            area.visibility = View.GONE
        }

        private fun reset() {
            if (replyOpen) closeReply()
            replyOpen = false
            actionsV.animate().cancel(); replyRow.animate().cancel()
            replyRow.visibility = View.GONE
            replyRow.alpha = 1f; replyRow.scaleX = 1f
            actionsV.alpha = 1f
            actionsV.translationX = 0f
        }

        fun bind(n: Notification, maxButtons: Int) {
            reset()
            val acts = n.actions ?: emptyArray()
            val replyAction = acts.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            // 답장 버튼을 항상 맨 앞에
            val ordered = (listOfNotNull(replyAction) + acts.filter { it !== replyAction }).take(maxButtons)
            actionsV.removeAllViews()
            ordered.forEach { a ->
                val b = TextView(ctx).apply {
                    text = a.title
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.pill_bg)
                    setPadding(dp(16), dp(7), dp(16), dp(7))
                    setOnClickListener { if (a === replyAction) openReply(a) else onAction(a) }
                }
                val lp = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                actionsV.addView(b, lp)
            }
            actionsV.visibility = View.VISIBLE
            area.visibility = if (ordered.isEmpty()) View.GONE else View.VISIBLE
        }

        /** 답장 버튼 → 버튼 줄이 사라지며 입력칸이 나타나고 키보드 올라옴 */
        private fun openReply(a: Notification.Action) {
            if (replyOpen) return
            replyOpen = true
            val hint = a.remoteInputs?.firstOrNull()?.label?.takeIf { it.isNotBlank() } ?: "답장"
            replyInput.hint = hint
            replyInput.setText("")
            val interp = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

            actionsV.animate().cancel(); replyRow.animate().cancel()
            actionsV.animate().alpha(0f).translationX(-dp(12).toFloat()).setStartDelay(0).setDuration(150)
                .withEndAction {
                    if (replyOpen) actionsV.visibility = View.INVISIBLE
                    actionsV.translationX = 0f
                }.start()
            replyRow.visibility = View.VISIBLE
            replyRow.alpha = 0f
            replyRow.scaleX = 0.92f
            replyRow.pivotX = 0f
            replyRow.animate().alpha(1f).scaleX(1f).setStartDelay(60).setDuration(240)
                .setInterpolator(interp).start()

            replyInput.setOnFocusChangeListener(null)
            replyInput.requestFocus()
            imm.showSoftInput(replyInput, 0)
            replyInput.postDelayed({ if (replyOpen) imm.showSoftInput(replyInput, 0) }, 80)
            onReplyOpen(true)

            val send = {
                val t = replyInput.text
                if (!t.isNullOrBlank()) {
                    onReply(a, t.toString())
                    replyInput.setText("")
                    closeReply()
                }
            }
            replySend.setOnClickListener { send() }
            replyInput.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
            replyInput.post {
                replyInput.setOnFocusChangeListener { _, has -> if (!has) closeReply() }
            }
        }

        /** 입력칸 → 다시 버튼 줄 */
        fun closeReply() {
            if (!replyOpen) return
            replyOpen = false
            replyInput.setOnFocusChangeListener(null)
            imm.hideSoftInputFromWindow(replyInput.windowToken, 0)
            onReplyOpen(false)
            card.isFocusableInTouchMode = true
            card.requestFocus()
            card.isFocusableInTouchMode = false

            actionsV.animate().cancel(); replyRow.animate().cancel()
            replyRow.animate().alpha(0f).scaleX(0.95f).setStartDelay(0).setDuration(150)
                .withEndAction {
                    if (!replyOpen) { replyRow.visibility = View.GONE; replyRow.scaleX = 1f }
                }.start()
            actionsV.visibility = View.VISIBLE
            actionsV.translationX = 0f
            actionsV.alpha = 0f
            actionsV.animate().alpha(1f).setStartDelay(60).setDuration(200).start()
        }
    }

    // ───────── 아래 접힌 카드 ─────────
    inner class CollapsedVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val line1: TextView = v.findViewById(R.id.line1)
        private val line2: TextView = v.findViewById(R.id.line2)
        private val timeV: TextView = v.findViewById(R.id.time)
        private val replyBox = ReplyBox(v)

        init {
            icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) =
                    o.setRoundRect(0, 0, view.width, view.height, 12 * density)
            }
            icon.clipToOutline = true
        }

        fun bind(g: NotiGroup) {
            val sbn = g.latest
            val n = sbn.notification
            icon.setImageDrawable(appIcon(sbn.packageName) ?: largeIcon(n))
            icon.imageTintList = null
            timeV.text = time(sbn)

            if (g.items.size >= 2) {
                // 여러 개면 최근 두 알림을 한 줄씩
                badge.visibility = View.VISIBLE
                badge.text = g.items.size.toString()
                line1.text = summary(g.items[0])
                line2.text = summary(g.items[1])
                line1.setTypeface(null, Typeface.NORMAL)
            } else {
                badge.visibility = View.GONE
                line1.text = title(n).ifEmpty { appName(sbn.packageName) }
                line1.setTypeface(null, Typeface.BOLD)
                val t = text(n)
                line2.text = t
            }
            line2.visibility = if (line2.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            // 답장 가능한 채팅 알림이면 접힌 카드에도 '답장' 버튼
            val canReply = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
            val subs: LinearLayout = itemView.findViewById(R.id.subItems)
            val isExp = g.pkg in expanded && g.items.size >= 2
            if (isExp) {
                // 펼침: 앱 이름 + 개수만 위에, 알림은 아래에 하나씩 (채팅은 각각 답장 가능)
                line1.text = "${appName(sbn.packageName)} · 알림 ${g.items.size}개"
                line1.setTypeface(null, Typeface.BOLD)
                line2.visibility = View.GONE
                replyBox.hide()
                subs.visibility = View.VISIBLE
                fillSubs(subs, g.items)
            } else {
                subs.visibility = View.GONE; subs.removeAllViews()
                if (canReply) replyBox.bind(n, maxButtons = 2) else replyBox.hide()
            }
            setupExpand(this, g)
            itemView.setOnClickListener { onOpen(sbn) }
        }

        private fun summary(s: StatusBarNotification): CharSequence {
            val n = s.notification
            val t = title(n)
            val x = text(n)
            return if (t.isEmpty()) x else if (x.isEmpty()) t else "$t: $x"
        }
    }

    @Suppress("unused")
    private fun tintWhite(v: ImageView) { v.imageTintList = ColorStateList.valueOf(Color.WHITE) }
}
