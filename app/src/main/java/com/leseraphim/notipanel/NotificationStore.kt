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

    fun update(all: Array<StatusBarNotification>?, ownPackage: String) {
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

    fun dismiss(sbn: StatusBarNotification) {
        service?.cancelNotification(sbn.key)
    }

    /** 여러 개를 하나씩 확실하게 지우기 */
    fun dismissMany(list: List<StatusBarNotification>) {
        val svc = service ?: return
        if (list.isEmpty()) return
        runCatching { svc.cancelNotifications(list.map { it.key }.toTypedArray()) }
            .onFailure { list.forEach { runCatching { svc.cancelNotification(it.key) } } }
    }

    fun dismissAll() {
        service?.cancelAllNotifications()
    }

    val connected get() = service != null
}
