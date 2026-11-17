# 深海光学（Abyssal Optics）· V4.0 视觉与动效规范

> 适用版本：DeepRun V4.0（基于 V3.3 + V3.4 功能基线）
> 代码实现见 `app/src/main/java/com/zcshou/gogogo/run/ui/glass/` 与 `run/ui/theme/`。

## 1. 设计原则

1. **克制即高级**：只让核心浮动层（主遥测卡、锁定坞站、战报弹窗）使用实时液态玻璃折射；功能卡（路线工坊、路线胶囊）用哑光实体材质，以“一虚一实”拉开空间层次，同时降低 GPU 采样。
2. **光是材质**：液态玻璃在纯黑上无效。任何玻璃卡正后方必须有环境背光或真实地图内容作为折射源。
3. **克制的渐变**：激活态不再使用单一荧光色，统一为“中心近白、边缘泛冰青”的光纤渐变。
4. **零额外耗电**：巡航 `RUNNING`、锁屏、地图拖动期间自动降级或冻结所有光学动效。

## 2. 色彩 Token

| Token | 深海光学（night） | 冰川棱镜（day） | 用途 |
| :--- | :--- | :--- | :--- |
| `dr_bg_canvas_base` | `#06080B` | `#F3F6FA` | 页面基底 |
| `dr_bg_ambient_glow` | `#29102A43` | `#40D5EEF5` | 玻璃卡后方径向背光 |
| `dr_glass_surface_tint` | `#B8111822` | `#CCFFFFFF` | 液态玻璃底色 |
| `dr_glass_rim_highlight` | `#337CE8F4` | `#FFFFFF` | 顶边菲涅尔高光 |
| `dr_matte_card_surface` | `#E60F151E` | `#EBFFFFFF` | 哑光实体卡 |
| `dr_accent_primary` | `#5CE1E6` | `#007A94` | 强调色 |
| `pf_volt` | `#C8FF00` | `#007A94` | 兼容色板主色 |
| 光纤渐变（代码） | `#EAFCFE → #9EF2F6 → #5CE1E6` | `#CFEFF4 → #5FCBDA → #007A94` | 激活态线条 / 主按钮 / 摇杆球 |

> Token 文件：`res/values/colors_deeprun.xml`、`res/values-night/colors_deeprun.xml`、`res/values/colors_paceflow.xml`、`res/values-night/colors_paceflow.xml`。
> 主题切换必须同步 `AppCompatDelegate.setDefaultNightMode()`，见 `ThemeHelper.applyGlobalNightMode()`。

## 3. 渲染分级（`DeepRunGlassManager`）

| 等级 | 条件 | 实现 |
| :--- | :--- | :--- |
| Tier 1 极致光学 | API 33+、非省电、非高对比度、渲染质量=极致光学 | AGSL `RuntimeShader`：圆角 SDF 边缘压缩 + RGB 色散 + 陀螺仪高光 |
| Tier 2 硬件毛玻璃 | API 31–32，或渲染质量=省电平衡 | `RenderNode` + `RenderEffect.createBlurEffect()` |
| Tier 3 经典磨砂 | API < 31、省电模式、高对比度、无硬件加速、`setRenderSuppressed(true)` | 半透磨砂 + 0.5dp 边界线 |

约束：
- 边缘斜坡厚度 `bevelWidth < 40% × min(w,h) / 2`；
- 同屏玻璃共享同一次背景采样（`GlassBackdrop` 12ms 帧窗），采样时玻璃自身 alpha=0；
- 地图上的玻璃使用 `setExternalBackdrop()` 吃 `BaiduMap.snapshot()`，避免 TextureView 在软件画布下不可采样的限制。

## 4. 动效与触觉

| 场景 | 规格 |
| :--- | :--- |
| Liquid Press | 按下缩放 0.97（120ms），松手过冲回弹（280ms，过冲 1.6） |
| 弹簧吸附 / 坞站滑入 | `SpringForce` 阻尼 0.72、刚度 380 |
| 水银分段器 | 过冲 0.9、320ms；目标标签微放大 1.06 |
| 声呐预热 | 5s 内同心环 1.2s 循环，光波扫过逐段点亮轨迹（暗段 alpha 60） |
| 焦散微光 | 两团 8s 周期正弦漂移光斑，alpha ≤ 8%；仅 Tier 1/2 且未冻结时运行 |
| 微调垫 / 角度滑块 | `HapticFeedbackConstants.CLOCK_TICK`（相机光圈环手感） |
| 锁定合焦 | `CONFIRM`（API 30+，旧系统回退 `LONG_PRESS`） |
| 悬浮胶囊闲置 | 3s 无触控淡化至 45%，触控立即恢复 100% |
| 跨窗模糊兜底 | `isCrossWindowBlurEnabled=false` → 胶囊/摇杆面板切 94% 磨砂 |

## 5. 双层光纤轨迹

- 底层：12dp、15% 透明度的冰川青漫反射光晕；
- 顶层：2.5dp、100% 不透明的白青渐变核心线（地图上使用 `PolylineOptions.colorsValues()` 逐段插值）；
- 当前位置：白核 + 冰青光晕的独立光点 Marker；
- 实现：`FiberTrail.create()`、`FiberPositionDot.icon()`、`RouteThumbnailView`、`RunReplayView`、`OpticalReticleView`。

## 6. 地图样式

- 暗色：`assets/map_style_dark.json`（深海海图：陆地 `#12161B`、水域 `#0A1A26`、道路 `#242B33`、POI 关闭）；
- 浅色：`assets/map_style_light.json`（银灰制图：陆地 `#F3F5F8`、水域 `#DCE8F2`、道路 `#FFFFFF`）；
- 建圈器 / 自由漫游统一 `TextureMapView`，拖动时玻璃采样降至 0.25x，松手恢复 1.0x。

## 7. 相关文件索引

| 能力 | 文件 |
| :--- | :--- |
| 玻璃容器 / 三层降级 | `run/ui/glass/LiquidGlassContainer.kt`、`DeepRunGlassManager.kt` |
| 共享背景采样 | `run/ui/glass/GlassBackdrop.kt` |
| 跨窗模糊 / 弹簧吸附 | `run/ui/glass/OverlayGlassStyle.kt` |
| 光纤渐变 | `run/ui/theme/FiberStyle.kt` |
| 双层光纤轨迹 | `run/ui/overlay/MapFiberTrail.kt`、`RouteThumbnailView.kt` |
| 光学取景目镜 | `run/ui/editor/OpticalReticleView.kt` |
| 水银分段器 | `run/ui/editor/MercuryTabStrip.kt` |
| 深海背景 | `run/ui/overlay/BathymetricBackdropView.kt` |
| 主题与 Token | `run/ui/theme/ThemeHelper.kt`、`res/values*/colors_*.xml` |
