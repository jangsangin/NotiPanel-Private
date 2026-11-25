package com.leseraphim.notipanel

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 폰에 온 알림을 받아서 NotificationStore 에 넣어주는 서비스 */
class NotiListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationStore.service = this
        refresh()
    }

    override fun onListenerDisconnected() {
        NotificationStore.service = null
        NotificationStore.update(emptyArray(), packageName)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val all = try { activeNotifications } catch (e: Exception) { null }
        NotificationStore.update(all, packageName)
    }
}
