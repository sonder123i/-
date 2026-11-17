package com.zcshou.gogogo.run.ui.home

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * 关于页：新品牌展示 + 全应用唯一的开源许可与致谢（GPLv3）。
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        private const val REPO_URL = "https://github.com/ZCShou/GoGoGo"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_about)
        ThemeHelper.padTopForStatusBar(findViewById(R.id.aboutRoot))

        findViewById<TextView>(R.id.aboutVersion).text =
            String.format(getString(R.string.about_version_format), BuildConfig.VERSION_NAME)

        val license = findViewById<TextView>(R.id.aboutLicense)
        license.text = buildLicenseText()
        license.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun buildLicenseText(): CharSequence {
        val title = getString(R.string.about_license_title)
        val body = getString(R.string.about_license_body)
        val label = getString(R.string.about_license_link_label)
        val builder = SpannableStringBuilder()
        builder.append(title)
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.append("\n\n")
        builder.append(body)
        builder.append("\n\n")
        builder.append(label)
        val urlStart = builder.length
        builder.append(REPO_URL)
        builder.setSpan(
            URLSpan(REPO_URL),
            urlStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }
}