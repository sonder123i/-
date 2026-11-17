package com.zcshou.gogogo.run.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.db.RunRouteEntity
import com.zcshou.gogogo.run.ui.home.RunHomeActivity

/**
 * 桌面长按快捷方式：为常用路线注册「1 步开跑」动态 Shortcut。
 */
object RunShortcutManager {

    // 固定三条静态快捷方式已占 3 席，动态路线快捷方式最多再占 2 席
    private const val MAX_SHORTCUTS = 2
    const val EXTRA_SHORTCUT_ROUTE_ID = "shortcut_route_id"

    fun syncShortcuts(context: Context, routes: List<RunRouteEntity>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = routes.take(MAX_SHORTCUTS).map { route ->
            ShortcutInfo.Builder(context, "route_${route.id}")
                .setShortLabel(route.name.take(10))
                .setLongLabel(route.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, RunHomeActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(EXTRA_SHORTCUT_ROUTE_ID, route.id)
                    }
                )
                .build()
        }
        runCatching { manager.setDynamicShortcuts(shortcuts) }
    }
}