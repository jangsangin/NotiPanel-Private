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

    // 블러된 그림에 위→아래로 점점 진해지는 투명도를 입히는 셰이더 (블러 자체는 기본 블러 사용)
    private const val MASK = """
        uniform shader content;
        uniform float startY;
        uniform float endY;
        half4 main(float2 p) {
            float t = smoothstep(startY, endY, p.y);
            return content.eval(p) * t;
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

    /**
     * 결과 = 선명한 원본 위에 (기본 블러 × 그라데이션 마스크) 를 겹침.
     * 블러 품질은 안드로이드 기본 블러 그대로라 자연스럽고, 윗부분은 원본 그대로 선명.
     */
    @RequiresApi(33)
    private fun make33(startY: Float, endY: Float, maxR: Float): RenderEffect {
        val mask = RuntimeShader(MASK)
        mask.setFloatUniform("startY", startY)
        mask.setFloatUniform("endY", endY)
        val blur = RenderEffect.createBlurEffect(maxR, maxR, Shader.TileMode.CLAMP)
        val maskedBlur = RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(mask, "content"), blur
        )
        val original = RenderEffect.createOffsetEffect(0f, 0f)
        return RenderEffect.createBlendModeEffect(original, maskedBlur, android.graphics.BlendMode.SRC_OVER)
    }
}
