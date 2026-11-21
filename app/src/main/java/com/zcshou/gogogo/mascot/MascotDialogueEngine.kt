package com.zcshou.gogogo.mascot

import java.util.Calendar
import kotlin.random.Random

/**
 * 鲸鲸本地台词库。
 *
 * 用户主动戳：走 [onUserPoke]，带连戳彩蛋与深夜识别；
 * 系统事件自动冒泡：走 [onSystemEvent]，带 18 秒防刷屏冷却。
 */
object MascotDialogueEngine {

    enum class State {
        IDLE,           // 首页待机
        POKE_COMBO,     // 连续戳脑袋彩蛋
        RUNNING,        // 发车/跑圈/漫游中
        SPRINT,         // 最后一圈冲刺
        FINISH,         // 跑完战报
        WARNING_WIFI,   // 专项：Wi-Fi 未关提醒
        WARNING_ENV,    // 专项：后台/权限体检异常
        LAZY,           // 暂停/打盹
        NIGHT,          // 深夜时段（23:00 - 05:00）
        SUPPORT         // 赞赏页
    }

    private const val COOLDOWN_MS = 18_000L
    private const val COMBO_WINDOW_MS = 4_500L
    private const val COMBO_THRESHOLD = 4

    private var lastAutoTriggerTime = 0L
    private var lastPokeTime = 0L
    private var pokeCount = 0
    private val lastPickedIndex = mutableMapOf<State, Int>()

    private val pool: Map<State, List<String>> = mapOf(
        State.IDLE to listOf(
            "哦鲸鲸……今天在床上跑几公里？",
            "主打一个赛博自律，精神长跑。",
            "配速别拉太满，容易跑成电瓶车。",
            "今天风太大，适合室内云跑。",
            "鞋带系好了，手机放好就行。",
            "体测的尽头，是优雅巡航。",
            "所有轨迹仅存本机，天知地知我知。",
            "空着也是空着，快建条路线跑起来！"
        ),
        State.POKE_COMBO to listOf(
            "再戳就晕车了，哦鲸鲸……",
            "别戳了别戳了，已老实！",
            "戳这么快，手速比配速还猛？",
            "再戳我就把配速调成博尔特了！",
            "电子功德 +1，鲸鲸好感 +1。",
            "干嘛呀，我在假装热身呢。"
        ),
        State.RUNNING to listOf(
            "云迹，启动！跟紧水晶旗！",
            "演得像一点，假装正在喘气。",
            "丝滑切入二道，优雅永不过时。",
            "300 米内平滑走，绝不瞬移露馅。",
            "外道超车！谁也看不出在躺平。",
            "今天的风甚是喧嚣（其实吹空调）。",
            "前方拐弯，抓稳扶手别甩飞。",
            "稳住配速，咱是正经耐力型选手。",
            "正在替你用双脚丈量地球（赛博版）。"
        ),
        State.SPRINT to listOf(
            "最后一圈！假装冲刺汗流浃背！",
            "准备撞线，表情管理保持住！",
            "最后一段，跟紧我的水晶旗冲呀！"
        ),
        State.FINISH to listOf(
            "打卡收工，这配速演得满分。",
            "哦鲸鲸！今日赛博卡路里已燃尽！",
            "深藏功与名，快去交差吧～",
            "腿不酸、气不喘，主打一个从容。",
            "完美闭环，今日步数榜危矣。"
        ),
        State.WARNING_WIFI to listOf(
            "哦鲸鲸…Wi-Fi 还连着宿舍呢？",
            "关掉 Wi-Fi 呀，演都不演了吗？",
            "哦鲸鲸…这样硬开，汗流浃背的可是你。"
        ),
        State.WARNING_ENV to listOf(
            "后台保护没开，一会我要被系统创死了。",
            "电池优化没放行，我随时准备润了。",
            "模拟位置没授权，我只是只普通小鲸鱼。",
            "红灯亮着呢，点一下去修吧大聪明。"
        ),
        State.LAZY to listOf(
            "呼…在梦里已经跑完半马了…zZZ",
            "暂停歇会儿，赛博小腿也抽筋了。",
            "哦鲸鲸…困了，明天再跑行不行…",
            "电量告急，原地进入节能摸鱼态。"
        ),
        State.NIGHT to listOf(
            "大晚上不睡觉，搞什么阴间夜跑？",
            "凌晨巡航？你是懂错峰出行的。",
            "呼…这么晚了，跑完早点睡哦鲸鲸…"
        ),
        State.SUPPORT to listOf(
            "投喂一点小鱼干，帮作者续杯咖啡～",
            "感谢打赏！今晚加餐冰镇磷虾！",
            "自愿随心，能帮到你鲸鲸就很开心！"
        )
    )

    /** 用户主动点击鲸鲸时调用，自带连戳彩蛋与深夜识别。 */
    fun onUserPoke(currentState: State): String = onUserPokeResult(currentState).text

    data class PokeResult(val text: String, val combo: Boolean)

    fun onUserPokeResult(currentState: State): PokeResult {
        val now = System.currentTimeMillis()
        if (now - lastPokeTime <= COMBO_WINDOW_MS) {
            pokeCount++
        } else {
            pokeCount = 1
        }
        lastPokeTime = now

        val targetState = when {
            pokeCount >= COMBO_THRESHOLD -> {
                pokeCount = 0
                State.POKE_COMBO
            }
            currentState == State.IDLE && isLateNight() -> State.NIGHT
            else -> currentState
        }
        return PokeResult(
            text = pickNonRepeating(targetState),
            combo = targetState == State.POKE_COMBO
        )
    }

    /** 系统事件自动冒泡时调用（带 18 秒防刷屏冷却）。 */
    fun onSystemEvent(state: State, bypassCooldown: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastAutoTriggerTime < COOLDOWN_MS) return null
        lastAutoTriggerTime = now
        return pickNonRepeating(state)
    }

    /** 根据当前时段把首页待机映射成深夜台词。 */
    fun contextState(base: State): State =
        if (base == State.IDLE && isLateNight()) State.NIGHT else base

    fun isLateNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 5
    }

    private fun pickNonRepeating(state: State): String {
        val list = pool[state].orEmpty()
        if (list.isEmpty()) return "哦鲸鲸……"
        if (list.size == 1) return list[0]

        val lastIdx = lastPickedIndex[state] ?: -1
        var nextIdx: Int
        do {
            nextIdx = Random.nextInt(list.size)
        } while (nextIdx == lastIdx)

        lastPickedIndex[state] = nextIdx
        return list[nextIdx]
    }
}