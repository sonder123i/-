# 第三方组件与许可声明 / Third-Party Notices

本仓库自身代码以 **GNU General Public License v3.0** 发布，完整条款见 [LICENSE](LICENSE)。

以下组件随应用一起分发，分别适用其自身许可。**本仓库的 GPLv3 授权不覆盖下列第三方组件的版权**；其中百度地图 SDK 为专有软件，不属于开源组件。

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| 百度地图 Android SDK（BaiduLBS） | 7.6.5 | 百度地图开放平台服务条款（专有） | `app/libs/BaiduLBS_Android.jar`、`app/libs/arm64-v8a/*.so`。仓库不包含 AK；再分发或商用前请自行确认百度条款，或替换为其他地图实现。 |
| AndroidX / Material Components | 见 `app/build.gradle` | Apache-2.0 | appcompat、constraintlayout、preference、lifecycle、dynamicanimation、room、datastore、material |
| Kotlin / kotlinx.coroutines | 2.0.21 / 1.8.1 | Apache-2.0 | |
| OkHttp | 4.12.0 | Apache-2.0 | |
| Markwon | 4.6.2 | Apache-2.0 | |
| XLog | 1.11.1 | Apache-2.0 | |
| Shizuku API / Provider | 13.1.5 | Apache-2.0 | 可选联动，未安装 Shizuku 时功能自动降级 |
| liquidglass（QWEA0/Liquid-Glass-Android） | v2.0.11 | MIT | JitPack 依赖 |
| GKD（gkd-kit/gkd） | — | GPL-3.0 | 仅界面选择器思路来源；如后续引入其代码，须保留版权与许可声明 |

## 再分发者注意

- 如果你只再分发本仓库的**源代码**，请保留 `LICENSE` 与本文件。
- 如果你再分发**APK**，还需一并满足百度地图 SDK 的分发条款；如无法满足，请从 `app/libs` 移除百度组件并替换地图实现。
- 应用内“关于 → 开源许可与致谢”是面向用户的许可入口，本文件是其完整清单。

*本文件为工程说明，不构成法律意见；正式商用前建议由法务复核。*
