package com.zcshou.gogogo.run.ui.home

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zcshou.gogogo.BuildConfig
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.ui.glass.GlassFeedback
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import com.zcshou.gogogo.run.update.UpdateConfig
import com.zcshou.gogogo.run.update.UpdateDialog
import com.zcshou.gogogo.run.update.UpdateManager
import com.zcshou.gogogo.run.update.UpdatePrefs
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 关于页：新品牌展示 + 全应用唯一的开源许可与致谢（GPLv3）。
 */
class AboutActivity : AppCompatActivity() {

    private var updateDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_about)
        ThemeHelper.padTopForStatusBar(findViewById(R.id.aboutRoot))

        val versionText = findViewById<TextView>(R.id.aboutVersion)
        versionText.text = String.format(
            getString(R.string.about_version_format),
            BuildConfig.VERSION_NAME
        ) + " · 点击检查更新"
        versionText.setOnClickListener {
            if (!UpdateConfig.enabled) {
                GlassFeedback.toast(this, "当前构建未配置更新源")
                return@setOnClickListener
            }
            if (updateDialog?.isShowing == true) return@setOnClickListener
            UpdateManager.getInstance(this).checkForUpdate(manual = true)
            updateDialog = UpdateDialog.show(this) { updateDialog = null }
        }

        val license = findViewById<TextView>(R.id.aboutLicense)
        license.text = buildLicenseText()

        // Star 的常驻入口：弹窗拒绝满 3 次后，这里仍然可以主动去点。
        findViewById<View>(R.id.aboutStar).setOnClickListener {
            StarPrompt.openRepo(this)
        }
        findViewById<View>(R.id.aboutFeedback).setOnClickListener {
            runCatching { startActivity(FeedbackActivity.intent(this)) }
        }
        findViewById<View>(R.id.aboutChangelog).setOnClickListener {
            runCatching { startActivity(ChangelogActivity.intent(this)) }
        }
        findViewById<View>(R.id.aboutBuild).setOnClickListener {
            BuildInfoDialog.show(this)
        }

        val autoCheck = findViewById<SwitchMaterial>(R.id.aboutAutoCheck)
        autoCheck.isChecked = UpdatePrefs.autoCheck(this)
        autoCheck.setOnCheckedChangeListener { _, checked ->
            UpdatePrefs.setAutoCheck(this, checked)
        }
        license.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        updateDialog = null
        super.onDestroy()
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
        builder.append(StarPrompt.REPO_URL)
        builder.setSpan(
            URLSpan(StarPrompt.REPO_URL),
            urlStart,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return builder
    }
}