package com.zcshou.gogogo.mascot

/**
 * 彩蛋拍立得。资源按名字从 drawable-nodpi 解析；未放入的图会自动隐藏整张卡。
 */
enum class MascotEgg(
    val resName: String,
    val caption: String,
    val showCaption: Boolean = true
) {
    WELCOME_ARMS("egg_welcome_arms", "哦鲸鲸！以后的路线就交给我来领跑啦！"),
    DRIVE("egg_drive", "配速拉满，已自动切换老司机模式！"),
    FLASHLIGHT("egg_flashlight", "夜跑探照灯已开，前方安全鲸！"),
    DESK_TAIL("egg_desk_tail", "赛博摸鱼中，顺便 rua 下尾巴～"),
    TOAST("egg_toast", "谢谢老板！今晚加餐吐司配磷虾！"),
    RICE_BOWL("egg_rice_bowl", "就吃大白米饭吗…投喂点小鱼干吧鲸…"),
    SULKING("egg_sulking", "不带我玩了？那我也背过身去…"),
    POKE_PRESSURE("egg_poke_pressure", "再戳就要把配速调成博尔特了！", showCaption = false),
    SLACKING_WHALE("egg_slacking_whale", "主打一个赛博自律，精神长跑！", showCaption = false),
    KEYBOARD("egg_keyboard", "正在疯狂敲代码帮你打掩护…")
}