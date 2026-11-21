package com.zcshou.gogogo.run.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.R
import com.zcshou.gogogo.run.guard.SetupGuard
import com.zcshou.gogogo.run.ui.glass.GlassFeedback
import com.zcshou.gogogo.run.ui.theme.ThemeHelper

/**
 * 权限设置图文教程。
 *
 * 四步做成手风琴，顶部用深色卡明确「下一步是哪一步」；默认只展开当前没完成的
 * 那一步，其余收成「标题 + 一句摘要」。点标题切换；从系统设置返回后会刷新状态，
 * 并给出下一步 —— 悬浮窗打不开时自动把人送回「解除受限设置」。
 *
 * 顺序按真实依赖排：开发者选项 → 模拟位置 → 解除受限设置 → 悬浮窗。
 * 侧载安装的应用基本都会被系统冻结悬浮窗，所以「解除限制」是正式步骤，不是可选。
 *
 * 截图按「品牌_步骤」命名放在 drawable-nodpi（如 setup_xiaomi_dev）；
 * 找不到对应资源时自动隐藏图片块，只保留文字路径。
 */
class SetupGuideActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STEP = "extra_step"
        private const val STATE_RESTRICT_CONFIRMED = "state_restrict_confirmed"

        /** 打开教程并直接展开某一步（0 开发者选项 / 1 模拟位置 / 2 解除限制 / 3 悬浮窗）。 */
        fun intent(context: Context, step: Int = -1): Intent =
            Intent(context, SetupGuideActivity::class.java).apply {
                if (step >= 0) putExtra(EXTRA_STEP, step)
            }
    }

    private data class Section(
        val root: Int,
        val header: Int,
        val body: Int,
        val chevron: Int,
        val summary: Int
    )

    private enum class FocusKind { DEV, MOCK, RESTRICT, OVERLAY }

    private data class FocusStep(
        val kind: FocusKind,
        val title: String,
        val why: String,
        val how: String,
        val action: String,
        val image: String,
        val caption: String
    )

    /** 顺序与页面一致：开发者选项 / 模拟位置 / 解除限制 / 悬浮窗。 */
    private val sections = listOf(
        Section(
            R.id.sectionDev, R.id.headerDev, R.id.bodyDev,
            R.id.chevronDev, R.id.summaryDev
        ),
        Section(
            R.id.sectionMock, R.id.headerMock, R.id.bodyMock,
            R.id.chevronMock, R.id.summaryMock
        ),
        Section(
            R.id.sectionRestrict, R.id.headerRestrict, R.id.bodyRestrict,
            R.id.chevronRestrict, R.id.summaryRestrict
        ),
        Section(
            R.id.sectionOverlay, R.id.headerOverlay, R.id.bodyOverlay,
            R.id.chevronOverlay, R.id.summaryOverlay
        )
    )

    private val stepTitles = arrayOf(
        "第 1 步 · 开启开发者选项",
        "第 2 步 · 选择模拟位置信息应用",
        "第 3 步 · 解除「受限设置」",
        "第 4 步 · 允许悬浮窗（可选）"
    )

    private val stepReasons = arrayOf(
        "所有后续权限的入口，先把它打开。",
        "让系统允许云迹注入模拟位置，这一步是开跑必需的。",
        "从浏览器或网盘安装基本都会被限制，先解除，悬浮窗开关才能打开。",
        "只影响悬浮控制胶囊。不开也能跑，暂停和收尾在通知栏完成。"
    )

    private var expandedIndex = -1
    private var nextActionStep = -1
    private var verifyingOverlay = false
    private var verifyingRestrict = false

    /** 首次 onResume 只刷新状态，不做「自动推进」，否则手动指定展开的步骤会被立刻切走。 */
    private var firstResume = true

    /**
     * 用户从「应用信息」返回后，先假定限制已解除，把人引到第 4 步；
     * 如果悬浮窗开关仍是灰的，第 4 步失败会再把人送回来。
     * 只在本页会话内有效，避免把「点过按钮」误当成「一定解除了」。
     */
    private var restrictConfirmed = false

    // ---------------- 一步一屏的引导（默认界面） ----------------

    private lateinit var focusPage: View
    private lateinit var detailPage: View
    private lateinit var focusScroll: ScrollView
    private lateinit var focusProgress: TextView
    private lateinit var focusDots: LinearLayout
    private lateinit var focusEyebrow: TextView
    private lateinit var focusTitle: TextView
    private lateinit var focusWhy: TextView
    private lateinit var focusImageBlock: View
    private lateinit var focusImage: ImageView
    private lateinit var focusCaption: TextView
    private lateinit var focusHowLabel: TextView
    private lateinit var focusHow: TextView
    private lateinit var focusAction: TextView
    private lateinit var focusDone: TextView
    private lateinit var focusManualDev: TextView
    private lateinit var focusSkip: TextView
    private lateinit var focusUsage: TextView
    private lateinit var focusDetails: TextView

    private var focusSteps: List<FocusStep> = emptyList()
    private var focusIndex = 0
    private var detailMode = false
    private var detailShotsBound = false

    private val detailShotImages = intArrayOf(
        R.id.shotVersionImage,
        R.id.shotDevImage,
        R.id.shotMockImage,
        R.id.shotSearchImage,
        R.id.shotOverlayImage,
        R.id.shotRestrictDialogImage,
        R.id.shotRestrictMenuImage
    )

    private lateinit var guideScroll: ScrollView
    private lateinit var heroEyebrow: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply(this)
        super.onCreate(savedInstanceState)
        // 转屏/进程重建后不要把「已解除限制」的确认状态丢掉。
        restrictConfirmed =
            savedInstanceState?.getBoolean(STATE_RESTRICT_CONFIRMED, false) ?: false
        ThemeHelper.enableEdgeToEdge(this)
        setContentView(R.layout.activity_setup_guide)
        ThemeHelper.padForSystemBars(findViewById(R.id.setupGuideRoot))

        guideScroll = findViewById(R.id.guideScroll)
        heroEyebrow = findViewById(R.id.guideHeroEyebrow)
        heroTitle = findViewById(R.id.guideHeroTitle)
        heroDesc = findViewById(R.id.guideHeroDesc)
        heroButton = findViewById(R.id.btnGuideNext)

        focusPage = findViewById(R.id.focusPage)
        detailPage = findViewById(R.id.detailPage)
        focusScroll = findViewById(R.id.focusScroll)
        focusProgress = findViewById(R.id.focusProgress)
        focusDots = findViewById(R.id.focusDots)
        focusEyebrow = findViewById(R.id.focusEyebrow)
        focusTitle = findViewById(R.id.focusTitle)
        focusWhy = findViewById(R.id.focusWhy)
        focusImageBlock = findViewById(R.id.focusImageBlock)
        focusImage = findViewById(R.id.focusImage)
        focusCaption = findViewById(R.id.focusCaption)
        focusHowLabel = findViewById(R.id.focusHowLabel)
        focusHow = findViewById(R.id.focusHow)
        focusAction = findViewById(R.id.btnFocusAction)
        focusDone = findViewById(R.id.btnFocusDone)
        focusManualDev = findViewById(R.id.focusManualDev)
        focusSkip = findViewById(R.id.btnFocusSkip)
        focusUsage = findViewById(R.id.btnFocusUsage)
        focusDetails = findViewById(R.id.btnFocusDetails)

        findViewById<View>(R.id.btnFocusBack).setOnClickListener { onBackRequested() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBackRequested()
        })

        findViewById<View>(R.id.btnSetupGuideBack).setOnClickListener { onBackRequested() }
        sections.forEachIndexed { index, section ->
            findViewById<View>(section.header).setOnClickListener { toggleSection(index) }
        }
        heroButton.setOnClickListener {
            if (nextActionStep >= 0) setExpanded(nextActionStep)
        }

        // 详细说明页的大图延迟到用户第一次点「查看详细说明」时再解码：
        // 7 张 1260 宽的截图同时驻留会多吃 20MB 以上，低端机没必要承担。

        // 截图目前只有小米一套；其他品牌用现有 SetupGuard 的品牌口诀兜底，
        // 避免让华为/OPPO 用户照着小米路径去菜单里找不存在的入口。
        findViewById<TextView>(R.id.guideOtherBrandDev).text =
            "开发者选项：" + SetupGuard.developerHint()
        findViewById<TextView>(R.id.guideOtherBrandRestrict).text =
            "解除限制：" + SetupGuard.restrictionMenuHint(this)

        findViewById<View>(R.id.btnGuideDeviceInfo).setOnClickListener { openDeviceInfoSettings() }
        findViewById<View>(R.id.btnGuideDevSettings).setOnClickListener {
            if (!SetupGuard.check(this).devOptions) {
                GlassFeedback.toast(this, "开发者选项还没开启，请先完成第 1 步")
            }
            openDeveloperSettings()
        }
        findViewById<View>(R.id.btnGuideCopyKeyword).setOnClickListener { copyMockKeyword() }
        findViewById<View>(R.id.btnGuideOverlayFromRestrict).setOnClickListener {
            // 用户主动点了「我已解除」，本次会话内不再把人拦在第 3 步。
            restrictConfirmed = true
            openOverlaySettings()
        }
        findViewById<View>(R.id.btnGuideOverlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.btnGuideAppInfo).setOnClickListener {
            verifyingRestrict = true
            openAppInfoSettings()
        }

        val state = SetupGuard.check(this)
        refreshStatus(state)
        val requested = intent.getIntExtra(EXTRA_STEP, -1)
        // 详细说明页保留手风琴；用户点「查看详细说明」时才看得到。
        val initial = if (requested in sections.indices) requested else -1
        setExpanded(initial, scroll = initial >= 0)

        // 默认进入一步一屏的引导：只显示当前需要做的那一步。
        focusSteps = buildFocusSteps()
        val requestedKind = when (requested) {
            0 -> FocusKind.DEV
            1 -> FocusKind.MOCK
            2 -> FocusKind.RESTRICT
            3 -> FocusKind.OVERLAY
            else -> null
        }
        val requestedIndex = requestedKind?.let { kind ->
            focusSteps.indexOfFirst { it.kind == kind }.takeIf { it >= 0 }
        }
        val fallbackIndex = focusSteps.indexOfFirst { !isFocusDone(it.kind, state) }
        renderFocus(requestedIndex ?: fallbackIndex)
    }

    override fun onResume() {
        super.onResume()
        if (!::guideScroll.isInitialized) return
        val state = SetupGuard.check(this)

        if (firstResume) {
            firstResume = false
            refreshStatus(state)
            return
        }

        if (focusPage.visibility == View.VISIBLE) {
            handleFocusResume(state)
            refreshStatus(state)
            return
        }

        if (verifyingOverlay) {
            verifyingOverlay = false
            if (!state.overlay) {
                if (needsRestrict(state)) {
                    refreshStatus(state)
                    setExpanded(2)
                    GlassFeedback.toast(this, "开关还是灰的？先按第 3 步解除限制")
                } else {
                    refreshStatus(state)
                    setExpanded(3)
                    GlassFeedback.toast(this, "悬浮窗还没打开，把「云迹」的开关打开")
                }
                return
            }
        }
        if (verifyingRestrict) {
            verifyingRestrict = false
            if (!state.overlay) {
                // 假定已解除，先引导去开悬浮窗；若开关仍灰，第 4 步会退回来。
                restrictConfirmed = true
                refreshStatus(state)
                setExpanded(3)
                GlassFeedback.toast(this, "解除后回到这里，把「云迹」的悬浮窗开关打开")
                return
            }
        }

        refreshStatus(state)
        // 刚完成的那一步如果已经变绿，自动展开下一步，用户不用自己判断。
        if (expandedIndex >= 0 && isSectionDone(expandedIndex, state)) {
            val next = currentStep(state)
            if (next >= 0 && next != expandedIndex) setExpanded(next)
        }
    }

    private fun toggleSection(index: Int) {
        if (expandedIndex != index) {
            setExpanded(index)
            return
        }
        // 收起后内容瞬间变短，ScrollView 的偏移会把标题顶出屏幕；
        // 收起的同时把这张卡滚回可视区顶部，视线不丢。
        setExpanded(-1, scroll = false)
        val root = findViewById<View>(sections[index].root)
        guideScroll.post {
            guideScroll.smoothScrollTo(0, (root.top - dp(12)).coerceAtLeast(0))
        }
    }

    private fun setExpanded(index: Int, scroll: Boolean = true) {
        expandedIndex = index
        sections.forEachIndexed { i, section ->
            val open = i == index
            findViewById<View>(section.body).visibility = if (open) View.VISIBLE else View.GONE
            findViewById<View>(section.summary).visibility = if (open) View.GONE else View.VISIBLE
            findViewById<View>(section.chevron).rotation = if (open) 180f else 0f
        }
        if (scroll && index >= 0) {
            val root = findViewById<View>(sections[index].root)
            guideScroll.post {
                guideScroll.smoothScrollTo(0, (root.top - dp(12)).coerceAtLeast(0))
            }
        }
    }

    /**
     * 侧载安装 + 悬浮窗尚未开启 → 大概率被「受限设置」拦住。
     * 用户从应用信息页回来确认过一次后，本次会话内不再重复拦在同一步。
     */
    private fun needsRestrict(state: SetupGuard.State): Boolean =
        !state.overlay && !restrictConfirmed && SetupGuard.isSideloaded(this)

    /** 当前第一个需要处理的步骤；全部就绪返回 -1。 */
    private fun currentStep(state: SetupGuard.State): Int = when {
        !state.devOptions -> 0
        !state.mockApp -> 1
        needsRestrict(state) -> 2
        !state.overlay -> 3
        else -> -1
    }

    private fun isSectionDone(index: Int, state: SetupGuard.State): Boolean = when (index) {
        0 -> state.devOptions
        1 -> state.mockApp
        2 -> !needsRestrict(state)
        3 -> state.overlay
        else -> false
    }

    private fun refreshStatus(state: SetupGuard.State = SetupGuard.check(this)) {
        bindStatus(R.id.statusDev, state.devOptions, "已完成", "待开启", R.color.pf_bad)
        bindStatus(R.id.statusMock, state.mockApp, "已完成", "待选择", R.color.pf_bad)
        when {
            state.overlay -> setStatus(R.id.statusRestrict, "无需", R.color.pf_ok)
            restrictConfirmed -> setStatus(R.id.statusRestrict, "已解除", R.color.pf_ok)
            SetupGuard.isSideloaded(this) ->
                setStatus(R.id.statusRestrict, "待解除", R.color.pf_bad)
            else -> setStatus(R.id.statusRestrict, "通常无需", R.color.pf_text_secondary)
        }
        bindStatus(R.id.statusOverlay, state.overlay, "已开启", "可选", R.color.pf_text_secondary)

        val step = currentStep(state)
        nextActionStep = step
        if (step >= 0) {
            heroEyebrow.text = "当前进度"
            heroTitle.text = stepTitles[step]
            heroDesc.text = stepReasons[step]
            heroButton.text = "继续第 ${step + 1} 步 →"
            heroButton.visibility = View.VISIBLE
        } else {
            heroEyebrow.text = "全部完成"
            heroTitle.text = "配置完成，可以开跑了"
            heroDesc.text = "开发者选项、模拟位置、受限设置和悬浮窗都已就绪。"
            heroButton.visibility = View.GONE
        }
    }

    private fun bindStatus(
        id: Int,
        done: Boolean,
        doneText: String,
        todoText: String,
        todoColor: Int
    ) {
        setStatus(id, if (done) doneText else todoText, if (done) R.color.pf_ok else todoColor)
    }

    private fun setStatus(id: Int, text: String, color: Int) {
        val label = findViewById<TextView>(id)
        label.text = text
        label.setTextColor(getColor(color))
    }

    /** 详细说明页的截图按需解码；离开时释放，避免大图长期占内存。 */
    private fun bindDetailShots() {
        if (detailShotsBound) return
        detailShotsBound = true
        bindShot(R.id.shotVersionBlock, R.id.shotVersionImage, "setup_xiaomi_version")
        bindShot(R.id.shotDevBlock, R.id.shotDevImage, "setup_xiaomi_dev")
        bindShot(R.id.shotMockBlock, R.id.shotMockImage, "setup_xiaomi_mock")
        bindShot(R.id.shotSearchBlock, R.id.shotSearchImage, "setup_xiaomi_search")
        bindShot(R.id.shotOverlayBlock, R.id.shotOverlayImage, "setup_xiaomi_overlay")
        bindShot(
            R.id.shotRestrictDialogBlock,
            R.id.shotRestrictDialogImage,
            "setup_xiaomi_restrict_dialog"
        )
        bindShot(
            R.id.shotRestrictMenuBlock,
            R.id.shotRestrictMenuImage,
            "setup_xiaomi_restrict_menu"
        )
    }

    private fun releaseDetailShots() {
        if (!detailShotsBound) return
        detailShotsBound = false
        detailShotImages.forEach { id ->
            findViewById<ImageView>(id).setImageDrawable(null)
        }
    }

    /**
     * 按资源名绑定截图；资源不存在时隐藏整个图片块（图 + 说明），
     * 让新品牌在补图之前也能只靠文字完成这一步。
     */
    private fun bindShot(blockId: Int, imageId: Int, resName: String) {
        val block = findViewById<View>(blockId)
        val resId = resources.getIdentifier(resName, "drawable", packageName)
        if (resId == 0) {
            block.visibility = View.GONE
            return
        }
        findViewById<ImageView>(imageId).setImageResource(resId)
    }

    /**
     * 系统设置页在不同 ROM 上可能只认「带包名」或「不带包名」的 Intent。
     * 先试精确入口，失败再退到通用入口，最后才提示用户手动找。
     */
    private fun launchWithFallback(first: Intent, second: Intent?, failureMessage: String) {
        if (runCatching { startActivity(first) }.isSuccess) return
        if (second != null && runCatching { startActivity(second) }.isSuccess) return
        GlassFeedback.toast(this, failureMessage)
    }

    private fun openDeviceInfoSettings() {
        launchWithFallback(
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
            "无法打开「关于手机」，请在系统设置中手动查找"
        )
    }

    private fun openDeveloperSettings() {
        launchWithFallback(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
            "无法打开开发者选项，请在系统设置中手动查找"
        )
    }

    private fun openAppInfoSettings() {
        launchWithFallback(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            "无法打开应用信息页，请在系统设置中手动查找"
        )
    }

    private fun openOverlaySettings() {
        verifyingOverlay = true
        launchWithFallback(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            "无法打开悬浮窗设置，请在系统设置中手动开启"
        )
    }

    private fun copyMockKeyword() {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("模拟位置", "模拟位置"))
        }.onSuccess {
            GlassFeedback.toast(this, "已复制，去系统设置搜索框粘贴即可")
        }.onFailure {
            GlassFeedback.toast(this, "复制失败，请手动输入「模拟位置」")
        }
    }

    // ---------------- 一步一屏的引导 ----------------

    private fun buildFocusSteps(): List<FocusStep> {
        val brand = SetupGuard.brandFamily()
        val field = devFieldName()
        val devEntry = when (brand) {
            SetupGuard.Brand.XIAOMI ->
                "进入「全部参数与信息」（HyperOS 2 是「版本信息」）→ "
            SetupGuard.Brand.OPPO -> "进入「版本信息」→ "
            SetupGuard.Brand.SAMSUNG -> "进入「软件信息」→ "
            else -> ""
        }
        fun caption(text: String): String =
            if (brand == SetupGuard.Brand.XIAOMI) text else "小米示例：$text"

        val steps = mutableListOf(
            FocusStep(
                FocusKind.DEV,
                "打开开发者模式",
                "这是所有权限的总开关。打开后，系统才允许云迹模拟位置。",
                "点下面的按钮 → ${devEntry}连续点「$field」7 次。\n\n看到「已进入开发者模式」就好了。",
                "去「关于手机」",
                "setup_xiaomi_version",
                caption("在「版本信息」页连点「软件版本号」7 次")
            ),
            FocusStep(
                FocusKind.MOCK,
                "让云迹提供位置",
                "告诉系统：模拟位置由云迹来提供。",
                "点下面的按钮 → 滑到页面最底部 → 选择模拟位置信息应用 → 选「云迹」。\n\n找不到就搜索「模拟位置」。",
                "去开发者选项",
                "setup_xiaomi_dev",
                caption("点这一行，再选「云迹」")
            )
        )
        if (SetupGuard.isSideloaded(this)) {
            steps += FocusStep(
                FocusKind.RESTRICT,
                "解除系统限制",
                "从浏览器安装的应用会被系统冻结权限。解除后，悬浮窗才能打开。",
                "点下面的按钮 → 右上角「⋮」→ 选择「解除设置限制 / 解除限制 / 允许受限制的设置」。",
                "打开应用信息",
                "setup_xiaomi_restrict_menu",
                caption("点右上角「⋮」里的「解除设置限制」")
            )
        }
        steps += FocusStep(
            FocusKind.OVERLAY,
            "打开悬浮窗（可选）",
            "用来在跑步软件上方显示控制胶囊。不开也能跑。",
            "点下面的按钮 → 找到「云迹」→ 打开开关。",
            "去打开悬浮窗",
            "setup_xiaomi_overlay",
            caption("打开「云迹」旁边的开关")
        )
        return steps
    }

    private fun isFocusDone(kind: FocusKind, state: SetupGuard.State): Boolean = when (kind) {
        FocusKind.DEV -> state.devOptions
        FocusKind.MOCK -> state.mockApp
        FocusKind.RESTRICT -> restrictConfirmed || state.overlay
        FocusKind.OVERLAY -> state.overlay
    }

    private fun renderFocus(index: Int) {
        detailMode = false
        focusPage.visibility = View.VISIBLE
        detailPage.visibility = View.GONE
        focusSteps = buildFocusSteps()
        focusIndex = index
        val total = focusSteps.size
        if (index !in focusSteps.indices) {
            renderFocusSuccess(total)
            return
        }
        val step = focusSteps[index]
        focusEyebrow.text = "第 ${index + 1} 步 / 共 $total 步"
        focusProgress.text = "${index + 1} / $total"
        focusTitle.text = step.title
        focusWhy.text = step.why
        focusHowLabel.visibility = View.VISIBLE
        focusHow.visibility = View.VISIBLE
        focusHow.text = step.how
        bindFocusImage(step.image, step.caption)
        focusAction.text = step.action
        focusAction.visibility = View.VISIBLE
        focusAction.setOnClickListener { performFocusAction(step.kind) }
        focusDone.visibility = View.VISIBLE
        focusDone.setOnClickListener { onFocusDone(step.kind) }
        // 个别 ROM 读不到开发者模式开关值；给一个手动确认出口，避免卡死在第一步。
        focusManualDev.visibility = if (step.kind == FocusKind.DEV) View.VISIBLE else View.GONE
        focusManualDev.setOnClickListener {
            SetupGuard.confirmManualDevOptions(this)
            GlassFeedback.toast(this, "已记录，继续下一步")
            advanceFocus()
        }
        focusSkip.visibility = if (step.kind == FocusKind.OVERLAY) View.VISIBLE else View.GONE
        focusUsage.visibility = View.GONE
        focusSkip.setOnClickListener {
            GlassFeedback.toast(this, "已跳过悬浮窗，回到云迹即可开跑")
            finish()
        }
        focusDetails.visibility = View.VISIBLE
        focusDetails.text = "看不明白？查看详细说明 →"
        focusDetails.setOnClickListener { openDetailsFor(step.kind) }
        renderDots(index, total)
        focusScroll.post { focusScroll.scrollTo(0, 0) }
    }

    private fun renderFocusSuccess(total: Int) {
        focusEyebrow.text = "全部完成"
        focusProgress.text = "$total / $total"
        focusTitle.text = "搞定，可以开跑了"
        focusWhy.text = "所需权限都已就绪。"
        focusHowLabel.visibility = View.GONE
        focusHow.visibility = View.GONE
        focusImageBlock.visibility = View.GONE
        focusAction.text = "回到云迹"
        focusAction.visibility = View.VISIBLE
        focusAction.setOnClickListener { finish() }
        focusDone.visibility = View.GONE
        focusManualDev.visibility = View.GONE
        focusSkip.visibility = View.GONE
        focusUsage.visibility = View.VISIBLE
        focusUsage.setOnClickListener {
            startActivity(UsageGuideActivity.intent(this))
            finish()
        }
        focusDetails.visibility = View.VISIBLE
        focusDetails.text = "查看详细步骤 →"
        focusDetails.setOnClickListener { openDetailsFor(null) }
        renderDots(total, total)
        focusScroll.post { focusScroll.scrollTo(0, 0) }
    }

    private fun bindFocusImage(name: String, caption: String) {
        val resId = resources.getIdentifier(name, "drawable", packageName)
        if (resId == 0) {
            focusImageBlock.visibility = View.GONE
            return
        }
        focusImageBlock.visibility = View.VISIBLE
        focusImage.setImageResource(resId)
        focusCaption.text = caption
    }

    private fun renderDots(current: Int, total: Int) {
        focusDots.removeAllViews()
        val size = dp(8)
        for (i in 0 until total) {
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(6)
            }
            val color = when {
                i < current -> getColor(R.color.pf_ok)
                i == current -> getColor(R.color.pf_volt)
                else -> getColor(R.color.pf_stroke)
            }
            dot.background = ContextCompat.getDrawable(this, R.drawable.bg_status_dot)?.mutate()
            dot.backgroundTintList = ColorStateList.valueOf(color)
            focusDots.addView(dot)
        }
    }

    /** 各品牌「版本号」那一行的叫法，只用于引导文案。 */
    private fun devFieldName(): String = when (SetupGuard.brandFamily()) {
        SetupGuard.Brand.XIAOMI, SetupGuard.Brand.VIVO -> "软件版本号"
        SetupGuard.Brand.SAMSUNG -> "编译编号"
        else -> "版本号"
    }

    /** 跳到「关于手机」后，Toast 里给出的下一步要跟着品牌走。 */
    private fun devToastHint(): String {
        val entry = when (SetupGuard.brandFamily()) {
            SetupGuard.Brand.XIAOMI -> "进入「全部参数与信息」→「版本信息」→ "
            SetupGuard.Brand.OPPO -> "进入「版本信息」→ "
            SetupGuard.Brand.SAMSUNG -> "进入「软件信息」→ "
            else -> "往下滑到最底部 → "
        }
        return "${entry}连点「${devFieldName()}」7 次"
    }

    private fun performFocusAction(kind: FocusKind) {
        when (kind) {
            FocusKind.DEV -> {
                // 系统设置页会屏蔽第三方悬浮窗（防点击劫持），但系统 Toast 能浮在上面，
                // 用它把「滑到底、点哪一行」再钉一次。
                Toast.makeText(
                    this,
                    devToastHint(),
                    Toast.LENGTH_LONG
                ).show()
                openDeviceInfoSettings()
            }
            FocusKind.MOCK -> {
                if (!SetupGuard.check(this).devOptions) {
                    GlassFeedback.toast(this, "先完成上一步：打开开发者模式")
                    return
                }
                Toast.makeText(
                    this,
                    "往下滑到最底部 → 点「选择模拟位置信息应用」→ 选「云迹」",
                    Toast.LENGTH_LONG
                ).show()
                openDeveloperSettings()
            }
            FocusKind.RESTRICT -> {
                verifyingRestrict = true
                openAppInfoSettings()
            }
            FocusKind.OVERLAY -> openOverlaySettings()
        }
    }

    private fun onFocusDone(kind: FocusKind) {
        val state = SetupGuard.check(this)
        when {
            kind == FocusKind.RESTRICT -> {
                restrictConfirmed = true
                GlassFeedback.toast(this, "已记录，继续下一步")
                advanceFocus()
            }
            isFocusDone(kind, state) -> {
                GlassFeedback.toast(this, "这一步完成了 ✓")
                advanceFocus()
            }
            else -> GlassFeedback.toast(this, "还没检测到，按上面的步骤再试一次")
        }
    }

    private fun advanceFocus() {
        val state = SetupGuard.check(this)
        focusSteps = buildFocusSteps()
        val next = focusSteps.indexOfFirst { !isFocusDone(it.kind, state) }
        if (next >= 0) {
            renderFocus(next)
        } else {
            renderFocusSuccess(focusSteps.size)
        }
    }

    /**
     * 从系统设置返回：能自动判断的步骤直接推进；悬浮窗失败则回退到解除限制。
     * 用户不需要理解状态，只需要回到云迹。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RESTRICT_CONFIRMED, restrictConfirmed)
    }

    private fun handleFocusResume(state: SetupGuard.State) {
        val step = focusSteps.getOrNull(focusIndex)
        if (step == null) {
            // 成功页停留期间权限被撤销时，重新回到缺失的那一步。
            val next = focusSteps.indexOfFirst { !isFocusDone(it.kind, state) }
            if (next >= 0) renderFocus(next)
            return
        }
        when (step.kind) {
            FocusKind.DEV, FocusKind.MOCK -> {
                if (isFocusDone(step.kind, state)) {
                    GlassFeedback.toast(this, "这一步完成了 ✓")
                    advanceFocus()
                } else {
                    GlassFeedback.toast(
                        this,
                        if (step.kind == FocusKind.DEV) {
                            "还没检测到，再连点「${devFieldName()}」几次"
                        } else {
                            "还没检测到，回到开发者选项选一下「云迹」"
                        }
                    )
                }
            }
            FocusKind.RESTRICT -> {
                if (verifyingRestrict) {
                    verifyingRestrict = false
                    if (!state.overlay) {
                        // 受限设置查不到状态，返回即视为已处理，继续下一步。
                        restrictConfirmed = true
                        GlassFeedback.toast(this, "继续：打开悬浮窗")
                        advanceFocus()
                    }
                }
            }
            FocusKind.OVERLAY -> {
                if (verifyingOverlay) {
                    verifyingOverlay = false
                    if (state.overlay) {
                        GlassFeedback.toast(this, "悬浮窗已打开 ✓")
                        advanceFocus()
                    } else if (SetupGuard.isSideloaded(this)) {
                        restrictConfirmed = false
                        val restrictIndex =
                            focusSteps.indexOfFirst { it.kind == FocusKind.RESTRICT }
                        if (restrictIndex >= 0) {
                            GlassFeedback.toast(this, "开关是灰的？先解除限制")
                            renderFocus(restrictIndex)
                        } else {
                            GlassFeedback.toast(this, "还没打开，再试一次")
                        }
                    } else {
                        GlassFeedback.toast(this, "还没打开，再试一次")
                    }
                } else if (state.overlay) {
                    advanceFocus()
                }
            }
        }
    }

    private fun openDetailsFor(kind: FocusKind?) {
        bindDetailShots()
        detailMode = true
        focusPage.visibility = View.GONE
        detailPage.visibility = View.VISIBLE
        refreshStatus()
        val index = when (kind) {
            FocusKind.DEV -> 0
            FocusKind.MOCK -> 1
            FocusKind.RESTRICT -> 2
            FocusKind.OVERLAY -> 3
            null -> -1
        }
        setExpanded(index)
    }

    private fun showFocusAgain() {
        releaseDetailShots()
        detailMode = false
        detailPage.visibility = View.GONE
        focusPage.visibility = View.VISIBLE
        val state = SetupGuard.check(this)
        refreshStatus(state)
        focusSteps = buildFocusSteps()
        val next = focusSteps.indexOfFirst { !isFocusDone(it.kind, state) }
        renderFocus(if (next >= 0) next else -1)
    }

    private fun onBackRequested() {
        if (detailMode) {
            showFocusAgain()
        } else {
            finish()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
