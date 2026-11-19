package com.zcshou.gogogo.run.ui.glass

import android.graphics.Canvas
import android.os.Build
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/** API 无关的焦散绘制接口，避免 API < 33 加载 RuntimeShader。 */
internal interface CausticShader {
    val available: Boolean
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        corner: Float,
        touchX: Float,
        touchY: Float,
        intensity: Float,
        radius: Float
    )
}

internal object CausticShaderFactory {
    fun create(): CausticShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return CausticShaderApi33().takeIf { it.available }
    }
}

/**
 * API 33+ 生成式焦散着色器。
 *
 * AGSL 只产出光，不采样背景；背景折射仍由 LiquidGlassView 的透镜管线负责。
 * 这样两层职责分离：SDF 折射 + 这层触摸焦散可以独立启停。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class CausticShaderApi33 : CausticShader {

    private val runtimeShader: RuntimeShader? = runCatching {
        RuntimeShader(
            """
            uniform float2 uSize;
            uniform float2 uTouch;
            uniform float uIntensity;
            uniform float uRadius;
            uniform float uCorner;

            float sdRoundRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p - halfSize) - (halfSize - r);
                return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            half4 main(float2 fragCoord) {
                float d = distance(fragCoord, uTouch);
                float g = exp(-pow(d / max(uRadius, 1.0), 2.0) * 2.1);
                float ring = smoothstep(uRadius * 0.26, 0.0, abs(d - uRadius * 0.55));
                float sd = sdRoundRect(fragCoord, uSize * 0.5, uCorner);
                float edge = smoothstep(8.0, 0.0, abs(sd));
                float near = smoothstep(uRadius * 2.6, 0.0, d);
                float rim = edge * near;
                float light = (g * 0.32 + ring * 0.16 + rim * 0.55) * uIntensity;
                float a = clamp(light * 1.15, 0.0, 0.55);
                float3 col = float3(0.88, 0.95, 1.0);
                return half4(half3(col * a), half(a));
            }
            """.trimIndent()
        )
    }.getOrNull()

    override val available: Boolean = runtimeShader != null

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        shader = runtimeShader
    }

    override fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        corner: Float,
        touchX: Float,
        touchY: Float,
        intensity: Float,
        radius: Float
    ) {
        val shader = runtimeShader ?: return
        shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
        shader.setFloatUniform("uTouch", touchX, touchY)
        shader.setFloatUniform("uIntensity", intensity)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uCorner", corner)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
