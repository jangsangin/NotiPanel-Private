package com.leseraphim.notipanel

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * 세로 그라데이션 블러.
 * startY 위쪽은 선명, startY→endY 구간에서 점점 흐려지고, endY 아래는 최대 블러.
 * 좌표는 카드(뷰) 안쪽 기준. 카드 모양(둥근 모서리) 밖으로는 번지지 않게 뷰 외곽선으로 잘라낸다.
 */
object ProgressiveBlur {

    private const val AGSL = """
        uniform shader content;
        uniform float startY;
        uniform float endY;
        uniform float maxR;
        half4 main(float2 p) {
            float t = clamp((p.y - startY) / max(endY - startY, 1.0), 0.0, 1.0);
            t = t * t * (3.0 - 2.0 * t);          // 부드러운 곡선
            float r = maxR * t;
            if (r < 0.75) { return content.eval(p); }
            half4 sum = half4(0.0);
            float total = 0.0;
            for (int i = -3; i <= 3; i++) {
                for (int j = -3; j <= 3; j++) {
                    float2 o = float2(float(i), float(j)) * (r / 3.0);
                    float w = exp(-float(i * i + j * j) / 8.0);
                    sum += content.eval(p + o) * w;
                    total += w;
                }
            }
            return sum / total;
        }
    """

    private data class State(val s: Int, val e: Int, val r: Int)

    /** 블러가 필요 없으면 r=0 */
    fun apply(v: View, startY: Float, endY: Float, maxR: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        // 카드 밖으로 안 번지게 둥근 외곽선으로 자르기
        if (!v.clipToOutline) v.clipToOutline = true

        val st = State(startY.toInt(), endY.toInt(), maxR.toInt())
        val prev = v.getTag(R.id.icon) as? State
        if (prev == st) return
        v.setTag(R.id.icon, st)

        if (st.r <= 0 || startY >= v.height) { v.setRenderEffect(null); return }
        if (Build.VERSION.SDK_INT >= 33) {
            v.setRenderEffect(make33(startY, endY, maxR))
        } else {
            // 구형: 겹치는 정도에 비례한 일반 블러
            val k = ((v.height - startY) / v.height.toFloat()).coerceIn(0f, 1f)
            val r = (maxR * k).coerceAtLeast(1f)
            v.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
        }
    }

    fun clear(v: View) = apply(v, 0f, 0f, 0f)

    @RequiresApi(33)
    private fun make33(startY: Float, endY: Float, maxR: Float): RenderEffect {
        val sh = RuntimeShader(AGSL)
        sh.setFloatUniform("startY", startY)
        sh.setFloatUniform("endY", endY)
        sh.setFloatUniform("maxR", maxR)
        return RenderEffect.createRuntimeShaderEffect(sh, "content")
    }
}
