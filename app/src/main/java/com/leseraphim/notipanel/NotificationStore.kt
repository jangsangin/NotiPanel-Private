package com.leseraphim.notipanel

import android.app.Notification
import android.service.notification.StatusBarNotification

/** 알림 목록을 보관하고 패널에 변경을 알려주는 저장소 */
object NotificationStore {
    var items: List<StatusBarNotification> = emptyList()
        private set

    var service: NotiListenerService? = null

    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** 시스템에 실제로 떠 있는 알림 전부 (묶음 요약 포함) */
    private var raw: List<StatusBarNotification> = emptyList()

    fun update(all: Array<StatusBarNotification>?, ownPackage: String) {
        raw = (all ?: emptyArray()).toList()
        items = (all ?: emptyArray())
            .filter { it.packageName != ownPackage }
            .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter { !isBlank(it) }
            .sortedByDescending { it.postTime }
        listeners.toList().forEach { it() }
    }

    /** 제목·내용이 하나도 없는 알림 (앱이 고정 알림을 비워 둔 것) → 패널에 안 띄움 */
    private fun isBlank(sbn: StatusBarNotification): Boolean {
        val ex = sbn.notification.extras ?: return true
        fun has(k: String) = !ex.getCharSequence(k)?.toString().isNullOrBlank()
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.any { !it.isNullOrBlank() } == true
        val msgs = (ex.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0) > 0
        return !(has(Notification.EXTRA_TITLE) || has(Notification.EXTRA_TEXT) ||
            has(Notification.EXTRA_BIG_TEXT) || has(Notification.EXTRA_SUB_TEXT) ||
            has(Notification.EXTRA_INFO_TEXT) || has(Notification.EXTRA_TITLE_BIG) || lines || msgs)
    }

    fun dismiss(sbn: StatusBarNotification) = dismissMany(listOf(sbn))

    /**
     * 여러 개를 하나씩 확실하게 지우기
     * + 지운 알림이 속한 묶음의 남은 알림이 없으면, 상단 알림창에 남는 빈 묶음 머리(요약 알림)도 같이 지움
     */
    fun dismissMany(list: List<StatusBarNotification>) {
        val svc = service ?: return
        if (list.isEmpty()) return
        val gone = list.map { it.key }.toMutableSet()
        val summaries = raw.filter { s ->
            s.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 && s.isClearable &&
                list.any { it.packageName == s.packageName && it.groupKey == s.groupKey } &&
                raw.none { c ->
                    c.key !in gone && c.key != s.key && c.packageName == s.packageName &&
                        c.groupKey == s.groupKey && c.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                }
        }
        val keys = (list.map { it.key } + summaries.map { it.key }).distinct()
        runCatching { svc.cancelNotifications(keys.toTypedArray()) }
            .onFailure { keys.forEach { k -> runCatching { svc.cancelNotification(k) } } }
    }

    /** 시스템 알림 목록을 지금 다시 읽어서 맞춤 (상단 알림창에서 지운 게 패널에 남지 않게) */
    fun resync() {
        val svc = service ?: return
        val all = runCatching { svc.activeNotifications }.getOrNull() ?: return
        update(all, svc.packageName)
    }

    fun dismissAll() {
        service?.cancelAllNotifications()
    }

    val connected get() = service != null
}
