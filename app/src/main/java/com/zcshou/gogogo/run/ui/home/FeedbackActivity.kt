package com.zcshou.gogogo.run.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.data.repository.RunRepository
import com.zcshou.gogogo.run.diagnostics.Diagnostics
import com.zcshou.gogogo.run.ui.glass.GlassFeedback
import com.zcshou.gogogo.run.ui.theme.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 问题反馈与诊断。
 *
 * 所有内容都在本地生成：诊断文本、日志尾部、GitHub issue 预填。分享通过
 * FileProvider 交给系统分享面板，不申请权限、不联网。
 */
class FeedbackActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, FeedbackActivity::class.java)
    }

    private lateinit var repository: RunRepository
    private lateinit var diagPreview: TextView
    private lateinit var logInfo: TextView
    private lateinit var copyButton: TextView
    private lateinit var shareButton: TextView
    private lateinit var issueButton: TextView

    private var diagText: String? = null
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_feedback)
        ThemeHelper.padForSystemBars(findViewById(R.id.feedbackRoot))

        repository = RunRepository(applicationContext)

        findViewById<View>(R.id.btnFeedbackBack).setOnClickListener { finish() }
        diagPreview = findViewById(R.id.feedbackDiagPreview)
        logInfo = findViewById(R.id.feedbackLogInfo)
        copyButton = findViewById(R.id.btnFeedbackCopy)
        shareButton = findViewById(R.id.btnFeedbackShare)
        issueButton = findViewById(R.id.btnFeedbackIssue)

        setActionsEnabled(false)
        updateLogInfo()

        copyButton.setOnClickListener {
            val text = diagText ?: return@setOnClickListener
            if (Diagnostics.copy(this, text)) {
                GlassFeedback.toast(this, getString(R.string.feedback_copy_done))
            }
        }
        shareButton.setOnClickListener { exportAndShare() }
        issueButton.setOnClickListener {
            val text = diagText ?: return@setOnClickListener
            if (!Diagnostics.openIssue(this, text)) {
                GlassFeedback.toast(this, getString(R.string.feedback_issue_failed))
            }
        }

        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                Diagnostics.build(this@FeedbackActivity, repository)
            }
            diagText = text
            diagPreview.text = text
            setActionsEnabled(true)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::logInfo.isInitialized) updateLogInfo()
    }

    private fun exportAndShare() {
        val text = diagText ?: return
        if (exporting) return
        exporting = true
        shareButton.isEnabled = false
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                Diagnostics.exportBundle(this@FeedbackActivity, text)
            }
            exporting = false
            shareButton.isEnabled = true
            if (file == null) {
                GlassFeedback.toast(this@FeedbackActivity, getString(R.string.feedback_share_failed))
            } else {
                Diagnostics.shareBundle(this@FeedbackActivity, file)
            }
        }
    }

    private fun updateLogInfo() {
        val log = Diagnostics.logFile(this)
        logInfo.text = if (log == null) {
            getString(R.string.feedback_log_missing)
        } else {
            "${log.name} · ${formatSize(log.length())} · ${formatTime(log.lastModified())}"
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        for (view in arrayOf<TextView>(copyButton, shareButton, issueButton)) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024L) "$bytes B" else String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}