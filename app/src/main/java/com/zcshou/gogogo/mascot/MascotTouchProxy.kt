package com.zcshou.gogogo.mascot

import android.view.MotionEvent
import android.view.View

/**
 * 让「探出父容器」的鲸鲸依然可点。
 *
 * 体检/赞赏页为了保持原有排版，让鲸鲸向上探出卡片（负 margin），可见身体落在
 * 父容器边界之外。Android 不会把触摸分发给父容器外的子 View，所以这里在页面
 * 根布局上做一次屏幕坐标命中转发：命中鲸鲸区域时直接触发一次点击。
 */
object MascotTouchProxy {

    fun install(root: View, mascot: MascotPeekView) {
        var pressed = false
        val loc = IntArray(2)
        root.setOnTouchListener { _, event ->
            if (mascot.visibility != View.VISIBLE) {
                pressed = false
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mascot.getLocationOnScreen(loc)
                    pressed = event.rawX >= loc[0] && event.rawX <= loc[0] + mascot.width &&
                        event.rawY >= loc[1] && event.rawY <= loc[1] + mascot.height
                    pressed
                }
                MotionEvent.ACTION_UP -> {
                    val hit = pressed
                    pressed = false
                    if (hit) mascot.triggerPoke()
                    hit
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    false
                }
                else -> pressed
            }
        }
    }
}