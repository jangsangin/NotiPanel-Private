package com.leseraphim.notipanel

import android.content.Context
import android.os.IBinder

/**
 * 앱 아이콘 뱃지(숫자) 끄기.
 * 런처가 알림 채널 설정을 무시하고 뱃지를 붙일 때, 앱 단위 '아이콘 배지' 자체를 끔.
 * 루트 권한으로 app_process 에서 시스템 알림 서비스에 직접 요청 (일반 앱은 못 하는 동작)
 */
object BadgeTool {
    /** 루트 셸에서 실행 */
    fun disable(ctx: Context) {
        val apk = ctx.applicationInfo.sourceDir
        RootShell.run(
            "CLASSPATH=$apk app_process /system/bin ${BadgeTool::class.java.name} " +
                "${ctx.packageName} ${ctx.applicationInfo.uid} >/dev/null 2>&1"
        )
    }

    /** app_process 로 루트 권한에서 실행되는 부분 */
    @JvmStatic
    fun main(args: Array<String>) {
        runCatching {
            val b = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            val inm = Class.forName("android.app.INotificationManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
            inm.javaClass.getMethod(
                "setShowBadge", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).invoke(inm, args[0], args[1].toInt(), false)
        }
    }
}
