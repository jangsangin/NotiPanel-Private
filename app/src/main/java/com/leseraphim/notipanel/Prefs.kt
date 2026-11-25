package com.leseraphim.notipanel

import android.content.Context
import android.content.SharedPreferences

/** 설정값 모음 */
object Prefs {
    const val FILE = "settings"
    const val KEY_ENABLED = "enabled"
    const val KEY_OPACITY = "bar_opacity"   // 사이드바 진하기 50~100 (예전 아이콘 값과 분리)
    const val KEY_SIZE = "size_dp"      // 터치 아이콘 크기(dp)
    const val KEY_BLUR = "blur"
    const val KEY_CARD = "card_dark"     // 노티바(카드) 진하기 0~100         // 패널 블러 강도(px)
    const val KEY_BAR_POS = "bar_pos"     // 사이드바 세로 위치(화면 위에서 %, 중심 기준)
    const val KEY_BAR_LEN = "bar_len"     // 사이드바 길이(화면 높이 %)
    const val KEY_BAR_W = "bar_w"         // 사이드바 두께(dp)
    const val KEY_POS_X = "pos_x"
    const val KEY_POS_Y = "pos_y"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(c: Context) = get(c).getBoolean(KEY_ENABLED, false)
    fun opacity(c: Context) = get(c).getInt(KEY_OPACITY, 100)
    fun sizeDp(c: Context) = get(c).getInt(KEY_SIZE, 64)
    fun cardDark(c: Context) = get(c).getInt(KEY_CARD, 0)
    fun blur(c: Context) = get(c).getInt(KEY_BLUR, 80)
    fun barPos(c: Context) = get(c).getInt(KEY_BAR_POS, 73)
    fun barLen(c: Context) = get(c).getInt(KEY_BAR_LEN, 10)
    fun barW(c: Context) = get(c).getInt(KEY_BAR_W, 5)
    fun posX(c: Context) = get(c).getInt(KEY_POS_X, -1)
    fun posY(c: Context) = get(c).getInt(KEY_POS_Y, -1)
}
