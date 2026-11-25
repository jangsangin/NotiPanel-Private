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
    private val onReply: (Notification.Action, CharSequence) -> Unit
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
        private val actionsV: LinearLayout = v.findViewById(R.id.actions)
        private val replyRow: View = v.findViewById(R.id.replyRow)
        private val replyInput: EditText = v.findViewById(R.id.replyInput)
        private val replySend: View = v.findViewById(R.id.replySend)

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
            // 같은 앱의 다른 알림 2개까지 한 줄씩
            g.items.drop(1).take(2).forEach {
                val s = SpannableStringBuilder(bold(title(it.notification))).append("  ").append(text(it.notification))
                messagesV.addView(line(s, 13f, 0.7f, maxLines = 1, top = dp(4)))
            }

            val pic = picture(n)
            pictureV.visibility = if (pic != null) View.VISIBLE else View.GONE
            pictureV.setImageBitmap(pic)

            // 답장 가능한 알림(카톡·문자 등)은 입력칸을 바로 보여줌
            val acts = n.actions ?: emptyArray()
            val replyAction = acts.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            if (replyAction != null) setupReply(replyAction) else replyRow.visibility = View.GONE

            // 나머지 버튼들 (읽음 등)
            actionsV.removeAllViews()
            val others = acts.filter { it !== replyAction }.take(3)
            others.forEach { a ->
                val b = TextView(ctx).apply {
                    text = a.title
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.pill_bg)
                    setPadding(dp(16), dp(7), dp(16), dp(7))
                    setOnClickListener { onAction(a) }
                }
                val lp = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                actionsV.addView(b, lp)
            }
            actionsV.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE

            itemView.setOnClickListener { onOpen(sbn) }
        }

        private fun setupReply(a: Notification.Action) {
            replyRow.visibility = View.VISIBLE
            val hint = a.remoteInputs?.firstOrNull()?.label?.takeIf { it.isNotBlank() } ?: "답장 보내기"
            replyInput.hint = hint
            val imm = ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            val send = {
                val t = replyInput.text
                if (!t.isNullOrBlank()) {
                    onReply(a, t.toString())
                    replyInput.setText("")
                    imm.hideSoftInputFromWindow(replyInput.windowToken, 0)
                }
            }
            replySend.setOnClickListener { send() }
            replyInput.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
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

    // ───────── 아래 접힌 카드 ─────────
    inner class CollapsedVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val line1: TextView = v.findViewById(R.id.line1)
        private val line2: TextView = v.findViewById(R.id.line2)
        private val timeV: TextView = v.findViewById(R.id.time)

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
