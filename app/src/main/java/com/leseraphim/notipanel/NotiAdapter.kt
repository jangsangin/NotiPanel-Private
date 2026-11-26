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
 * 목록의 한 줄.
 * SUMMARY: 묶음을 카드 한 장으로 (두 줄 요약) / HEADER: 묶음을 펼쳤을 때 앱 이름 줄 / SINGLE: 알림 한 개
 * g: 이 줄이 대표하는 알림들 (밀어서 지울 때 함께 지워짐)
 */
class Row(val kind: Int, val g: NotiGroup, val key: String) {
    companion object { const val SUMMARY = 0; const val HEADER = 1; const val SINGLE = 2 }
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
    private val onReplyOpen: (Boolean, View?) -> Unit = { _, _ -> },
    private val onToggle: (List<Int>) -> Unit = {},
    private val onStructure: (() -> Unit) -> Unit = { it() }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EXPANDED = 0
        private const val TYPE_COLLAPSED = 1
        private const val TYPE_HEADER = 2

        fun group(list: List<StatusBarNotification>): List<NotiGroup> =
            list.groupBy { it.packageName }
                .map { (pkg, l) -> NotiGroup(pkg, l.sortedByDescending { it.postTime }) }
                .sortedByDescending { it.latest.postTime }
    }

    /** 원본 묶음들 */
    private var sourceGroups: List<NotiGroup> = emptyList()
    /** 화면에 보이는 줄들 */
    var rows: List<Row> = emptyList()
        private set
    /** 줄마다 대표하는 알림들 (패널 쪽에서 쓰는 이름 그대로 유지) */
    val groups: List<NotiGroup> get() = rows.map { it.g }

    /** 눌러서 카드별로 갈라 펼친 묶음 (앱 패키지) */
    val splitPkgs = HashSet<String>()
    val hasSplit get() = rows.any { it.kind == Row.HEADER }

    private val appNames = HashMap<String, String>()

    /** 화살표로 바꾼 카드별 펼침 상태 (기본: 모두 접힘) */
    val expandOverride = HashMap<String, Boolean>()

    fun isExpanded(pos: Int): Boolean {
        val r = rows.getOrNull(pos) ?: return false
        if (r.kind != Row.SINGLE) return false
        return expandOverride[r.key] ?: false
    }

    private fun buildRows(src: List<NotiGroup>): List<Row> {
        val out = ArrayList<Row>()
        for (g in src) {
            if (g.items.size >= 2 && g.pkg in splitPkgs) {
                out += Row(Row.HEADER, g, "H:${g.pkg}")
                g.items.forEach { out += Row(Row.SINGLE, NotiGroup(g.pkg, listOf(it)), "N:${it.key}") }
            } else if (g.items.size >= 2) {
                out += Row(Row.SUMMARY, g, "S:${g.pkg}")
            } else {
                out += Row(Row.SINGLE, g, "N:${g.latest.key}")
            }
        }
        return out
    }

    /** 묶음 펼치기/합치기 (패널이 애니메이션과 함께 처리) */
    fun setSplit(pkg: String, split: Boolean) {
        onStructure {
            if (split) splitPkgs += pkg else splitPkgs -= pkg
            submitRows(buildRows(sourceGroups))
        }
    }

    private val appIcons = HashMap<String, Drawable?>()
    private val density = ctx.resources.displayMetrics.density
    private val cardDark = Prefs.cardDark(ctx)

    /** 노티바 배경: 0% = 밝은 반투명 유리, 100% = 짙은 회색 유리 (설정에서 조절) */
    private fun cardBg(): android.graphics.drawable.Drawable {
        val t = cardDark / 100f
        fun mix(a: Int, b: Int): Int {
            fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt()
            return Color.argb(
                ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
                ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b))
            )
        }
        val top = mix(Color.argb(0x52, 255, 255, 255), Color.argb(238, 62, 62, 62))
        val mid = mix(Color.argb(0x33, 255, 255, 255), Color.argb(238, 52, 52, 52))
        val end = mix(Color.argb(0x24, 255, 255, 255), Color.argb(240, 44, 44, 44))
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, mid, end)
        ).apply {
            cornerRadius = 26 * density
            setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(0x59, 255, 255, 255))
        }
    }

    init { setHasStableIds(true) }

    /** 바뀐 카드만 골라서 갱신 (전체 다시 그리기 X → 끊김 방지) */
    fun submit(list: List<NotiGroup>) {
        sourceGroups = list
        // 사라진 묶음은 펼침 상태도 정리
        splitPkgs.retainAll(list.filter { it.items.size >= 2 }.map { it.pkg }.toSet())
        submitRows(buildRows(list))
    }

    private fun submitRows(list: List<Row>) {
        val old = rows
        rows = list
        if (old.isEmpty() || list.isEmpty()) { notifyDataSetChanged(); return }
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object :
            androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].key == list[n].key
            override fun areContentsTheSame(o: Int, n: Int) = sig(old[o].g) == sig(list[n].g)
        }, true)
        diff.dispatchUpdatesTo(this)
    }

    private fun sig(g: NotiGroup) = g.items.joinToString(",") {
        val ex = it.notification.extras
        "${it.key}:${it.postTime}:${ex.getCharSequence(Notification.EXTRA_TEXT)}"
    }

    /** 스와이프로 지운 묶음을 바로 목록에서 빼기 (알림 서버 응답 기다리지 않음) */
    fun removeAt(pos: Int) {
        val r = rows.getOrNull(pos) ?: return
        val gone = r.g.items.map { it.key }.toSet()
        // 원본에서도 빼고 다시 구성 (묶음 제목만 남거나 1개만 남으면 알아서 정리)
        sourceGroups = sourceGroups.mapNotNull { g ->
            val left = g.items.filter { it.key !in gone }
            if (left.isEmpty()) null else NotiGroup(g.pkg, left)
        }
        submitRows(buildRows(sourceGroups))
    }

    override fun getItemCount() = rows.size
    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()
    override fun getItemViewType(position: Int) = when {
        rows[position].kind == Row.HEADER -> TYPE_HEADER
        isExpanded(position) -> TYPE_EXPANDED
        else -> TYPE_COLLAPSED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(ctx)
        if (viewType == TYPE_HEADER) return HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        return if (viewType == TYPE_EXPANDED)
            ExpandedVH(inf.inflate(R.layout.item_expanded, parent, false))
        else
            CollapsedVH(inf.inflate(R.layout.item_collapsed, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val g = groups[position]
        when (holder) {
            is HeaderVH -> holder.bind(rows[position])
            is ExpandedVH -> holder.bind(g)
            is CollapsedVH -> holder.bind(g)
        }
    }

    /** 묶음을 펼쳤을 때 맨 위 앱 이름 줄: 누르면 다시 카드 한 장으로 합침 */
    inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.headerTitle)
        private val btn: View = v.findViewById(R.id.headerBtn)
        fun bind(r: Row) {
            title.text = appName(r.g.pkg)
            btn.rotation = 180f
            val collapse = View.OnClickListener {
                btn.animate().rotation(0f).setDuration(180).start()
                // 펼쳐 둔 알림들의 펼침 상태도 정리
                r.g.items.forEach { expandOverride.remove("N:${it.key}") }
                btn.postDelayed({ setSplit(r.g.pkg, false) }, 100)
            }
            btn.setOnClickListener(collapse)
            itemView.setOnClickListener(collapse)
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

    /** 대화형 메시지 한 개: 보낸 사람, 내용, 프로필 사진 */
    class Msg(val sender: CharSequence?, val text: CharSequence, val avatar: Drawable?, val key: String?)

    private fun chatMessages(n: Notification): List<Msg> {
        val arr: Array<Parcelable>? = if (Build.VERSION.SDK_INT >= 33)
            n.extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        return arr?.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val t = b.getCharSequence("text") ?: return@mapNotNull null
            val person = runCatching {
                if (Build.VERSION.SDK_INT >= 33) b.getParcelable("sender_person", android.app.Person::class.java)
                else @Suppress("DEPRECATION") b.getParcelable("sender_person") as? android.app.Person
            }.getOrNull()
            val name = person?.name ?: b.getCharSequence("sender")
            val av = runCatching { person?.icon?.loadDrawable(ctx) }.getOrNull()
            Msg(name, t, av, person?.key ?: person?.uri ?: name?.toString())
        } ?: emptyList()
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
            itemView.background = cardBg()
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

            // 대화형(카톡 등): 상태바 알림창처럼 보낸 사람별로 프로필 사진 + 이름 + 말한 내용
            messagesV.removeAllViews()
            val chat = chatMessages(n).takeLast(6)
            val lpM = messagesV.layoutParams as ViewGroup.MarginLayoutParams
            if (chat.isNotEmpty()) {
                lpM.marginStart = 0
                messagesV.layoutParams = lpM
                var i = 0
                while (i < chat.size) {
                    val who = chat[i].key
                    var j = i
                    while (j + 1 < chat.size && chat[j + 1].key == who) j++
                    messagesV.addView(senderBlock(chat.subList(i, j + 1), first = i == 0))
                    i = j + 1
                }
            } else {
                lpM.marginStart = dp(52)
                messagesV.layoutParams = lpM
                val t = text(n)
                if (t.isNotEmpty()) messagesV.addView(line(t, 14f, 0.95f, maxLines = 4))
            }
            // 같은 앱의 다른 알림 2개까지 한 줄씩
            setupExpand(this, g)
            g.items.drop(1).take(2).forEach {
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

        /** 한 사람이 연달아 보낸 메시지 묶음: 왼쪽 프로필 사진, 오른쪽 이름 + 내용 */
        private fun senderBlock(ms: List<Msg>, first: Boolean): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (first) dp(4) else dp(12) }
            }
            val m0 = ms.first()
            val name = m0.sender?.toString()?.takeIf { it.isNotBlank() } ?: "나"
            val av = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (m0.avatar != null) setImageDrawable(m0.avatar)
                else setImageDrawable(letterAvatar(name))
            }
            round(av)
            row.addView(av, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(line(name, 15f, 0.85f, maxLines = 1, top = 0))
            ms.forEach { col.addView(line(it.text, 15f, 1f, maxLines = 3, top = dp(2))) }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }

        /** 프로필 사진이 없으면 이름 첫 글자 동그라미 */
        private fun letterAvatar(name: String): Drawable {
            val size = dp(40)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            val hue = (name.hashCode() and 0xFFFF) % 360
            val pBg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.35f, 0.75f))
            }
            c.drawCircle(size / 2f, size / 2f, size / 2f, pBg)
            val pT = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = size * 0.45f; textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            val ch = name.trim().take(1)
            c.drawText(ch, size / 2f, size / 2f - (pT.descent() + pT.ascent()) / 2, pT)
            return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
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
    /**
     * 오른쪽 위 화살표
     * - 묶음 카드(여러 알림): 누르면 알림마다 카드로 갈라져 펼쳐짐
     * - 알림 카드: ⌄ 누르면 내용 펼침, ⌃ 누르면 접힘 (한 번에 하나만 펼침)
     */
    private fun setupExpand(holder: RecyclerView.ViewHolder, g: NotiGroup) {
        val btn: ImageView = holder.itemView.findViewById(R.id.expandBtn)
        val pos0 = holder.bindingAdapterPosition
        val row = rows.getOrNull(pos0)
        btn.animate().cancel()
        if (row?.kind == Row.SUMMARY) {
            btn.rotation = 0f
            val split = View.OnClickListener {
                btn.animate().rotation(180f).setDuration(180).start()
                btn.postDelayed({ setSplit(g.pkg, true) }, 100)
            }
            btn.setOnClickListener(split)
            holder.itemView.setOnClickListener(split)   // 묶음 카드는 눌러도 펼쳐짐
            return
        }
        val isExp = pos0 != RecyclerView.NO_POSITION && isExpanded(pos0)
        btn.rotation = if (isExp) 180f else 0f
        btn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            val r = rows.getOrNull(pos) ?: return@setOnClickListener
            val next = !isExpanded(pos)
            val changed = mutableListOf(pos)
            if (next) {
                // 한 번에 하나만 펼침: 다른 펼친 카드는 접기
                rows.forEachIndexed { i, other ->
                    if (i != pos && isExpanded(i)) { expandOverride[other.key] = false; changed += i }
                }
            }
            expandOverride[r.key] = next
            btn.animate().rotation(if (next) 180f else 0f).setDuration(180).start()
            btn.postDelayed({ onToggle(changed) }, 120)
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
            onReplyOpen(true, card)

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
            onReplyOpen(false, null)
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
            itemView.background = cardBg()
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
            // 접힌 카드는 두 줄만 (답장·읽음 버튼은 화살표로 펼치면 나옴)
            replyBox.hide()
            itemView.setOnClickListener { onOpen(sbn) }
            setupExpand(this, g)
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
