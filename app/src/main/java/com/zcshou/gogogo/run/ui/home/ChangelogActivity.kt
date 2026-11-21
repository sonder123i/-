package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import io.noties.markwon.Markwon

/**
 * 更新日志：直接渲染 assets/changelog.md。
 *
 * 发版时只需要维护这一个 Markdown 文件；Markwon 已是现有依赖，不额外引库。
 */
class ChangelogActivity : AppCompatActivity() {

    companion object {
        private const val ASSET = "changelog.md"

        fun intent(context: Context): Intent = Intent(context, ChangelogActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_changelog)
        ThemeHelper.padForSystemBars(findViewById(R.id.changelogRoot))

        findViewById<View>(R.id.btnChangelogBack).setOnClickListener { finish() }

        val textView = findViewById<TextView>(R.id.changelogText)
        val markdown = runCatching {
            assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("# 更新日志\n\n暂无内容")
        Markwon.create(this).setMarkdown(textView, markdown)
        textView.movementMethod = LinkMovementMethod.getInstance()
    }
}