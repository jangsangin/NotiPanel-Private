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
            .sortedByDescending { it.postTime }
        listeners.toList().forEach { it() }
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
