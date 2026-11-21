package com.zcshou.gogogo.mascot

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi

/**
 * 图片加载：现在都是静态 WebP；如果以后换成多帧动画 WebP，Android 9+
 * 会自动识别成 [AnimatedImageDrawable] 并无限循环播放，不需要改调用方。
 */
object MascotAssetLoader {

    fun load(imageView: ImageView, @DrawableRes resId: Int) {
        stop(imageView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (loadDecoded(imageView, resId)) return
        }
        imageView.setImageResource(resId)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun loadDecoded(imageView: ImageView, @DrawableRes resId: Int): Boolean =
        runCatching {
            val source = ImageDecoder.createSource(imageView.resources, resId)
            val drawable = ImageDecoder.decodeDrawable(source)
            imageView.setImageDrawable(drawable)
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                drawable.start()
            }
            true
        }.getOrDefault(false)

    fun stop(imageView: ImageView) {
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
    }
}