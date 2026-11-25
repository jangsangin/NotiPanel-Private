package com.leseraphim.notipanel

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 카드가 겹쳐 쌓인 목록용 RecyclerView.
 * 손가락 아래 카드를 고를 때 '화면에 실제로 그려진 위치·크기'와 '앞뒤(Z) 순서'를 기준으로 고른다.
 * (기본 동작은 뒤에 깔린 카드를 먼저 잡아서, 좌우 밀기가 첫 터치에 안 먹었음)
 */
class StackRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : RecyclerView(context, attrs, defStyle) {

    override fun findChildViewUnder(x: Float, y: Float): View? {
        val kids = (0 until childCount).map { getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.alpha > 0.05f }
            // 앞에 있는 카드(Z가 큰 것, 같으면 나중에 그려진 것)부터
            .sortedWith(compareByDescending<View> { it.z }.thenByDescending { indexOfChild(it) })
        for (c in kids) {
            val sx = c.scaleX
            val sy = c.scaleY
            val left = c.left + c.translationX + c.pivotX * (1 - sx)
            val top = c.top + c.translationY + c.pivotY * (1 - sy)
            val right = left + c.width * sx
            val bottom = top + c.height * sy
            if (x in left..right && y in top..bottom) return c
        }
        return null
    }
}
