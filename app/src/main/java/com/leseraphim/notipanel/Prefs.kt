package com.leseraphim.notipanel

import android.content.Context
import android.content.SharedPreferences

/** 설정값 모음 */
object Prefs {
    const val FILE = "settings"
    const val KEY_ENABLED = "enabled"
    const val KEY_OPACITY = "opacity"   // 터치 아이콘 불투명도 0~100
    const val KEY_SIZE = "size_dp"      // 터치 아이콘 크기(dp)
    const val KEY_BLUR = "blur"         // 패널 블러 강도(px)
    const val KEY_POS_X = "pos_x"
    const val KEY_POS_Y = "pos_y"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = get(c).getBoolean(KEY_ENABLED, false)
    fun opacity(c: Context) = get(c).getInt(KEY_OPACITY, 60)
    fun sizeDp(c: Context) = get(c).getInt(KEY_SIZE, 64)
    fun blur(c: Context) = get(c).getInt(KEY_BLUR, 80)
    fun posX(c: Context) = get(c).getInt(KEY_POS_X, -1)
    fun posY(c: Context) = get(c).getInt(KEY_POS_Y, -1)
}
