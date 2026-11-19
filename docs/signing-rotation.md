# 发布签名轮换（APK Signature Scheme v3）

> 状态：**工具已就绪，尚未执行。** 执行前必须先完成下面「前置条件」里的两项确认，
> 否则会造成更新中断或百度地图鉴权失败。

## 为什么要轮换

当前发布签名 `keystore/GoGoGo.jks` 来自上游公开仓库，证书和密码都曾公开。
任何人拿到它都能签出与官方同证书的 APK，进而伪装成“官方更新”诱导覆盖安装。
应用内更新清单有 ECDSA 签名保护，但侧载场景不受保护。

## 轮换的真实代价

| 场景 | 结果 |
|---|---|
| 已安装 1.13.x 的用户点应用内“检查更新” | ❌ 旧版本会校验“新 APK 签名 == 当前安装包签名”，轮换版会被拒绝 |
| 用户从官网 / GitHub 手动下载安装 | ✅ Android 9+ 通过 v3 lineage 允许覆盖安装；Android 8.1 需卸载重装 |
| 百度地图鉴权 | ⚠️ 新证书 SHA1 必须加入百度控制台安全码，否则新系统上地图不可用 |
| 轮换之后的版本之间更新 | ✅ 恢复正常 |

因此轮换不是一次普通发版，而是一次**手动迁移事件**，需要发布说明明确告知用户。

## 前置条件（缺一不可）

1. **百度地图控制台**：确认 AK 支持把新证书 SHA1 加入安全码，或准备新的 AK。
   - 安全码格式：`<SHA1>;com.zcshou.gogogo`
   - 新 SHA1 由轮换脚本输出。
2. **真机验证**：
   - Android 9+：旧版 → 新版覆盖安装，数据保留；
   - Android 8.1：确认只能卸载重装，并在发布说明中写清；
   - 覆盖安装后确认地图能鉴权、定位正常。

## 执行步骤

### 1. 本地生成新密钥与 lineage

```powershell
powershell -ExecutionPolicy Bypass -File scripts/rotate_signing_key.ps1 `
  -OldKeystore keystore/GoGoGo.jks `
  -OldStorePass <旧密码> `
  -OldAlias GoGoKey
```

脚本会：

- 生成 `~/projects/cloudtrace-signing/cloudtrace-release.jks`（4096 位 RSA，随机密码）
- 生成 `~/projects/cloudtrace-signing/lineage.bin`
- 打印新证书 SHA1/SHA256、keystore 与 lineage 的 base64

**立刻离线备份整个 `cloudtrace-signing` 目录。** 丢失后无法再给已轮换用户签发更新。

### 2. 更新百度安全码

把脚本输出的 **新 SHA1** 加到百度地图控制台，格式：

```
<SHA1>;com.zcshou.gogogo
```

在确认旧客户端仍能鉴权之前不要删除旧 SHA1。

### 3. 配置 GitHub Secrets

| Secret | 内容 |
|---|---|
| `ROTATE_SIGNING_KEY` | 新 keystore 的 base64 |
| `ROTATE_KEY_PASSWORD` | 新 keystore 密码 |
| `ROTATE_KEY_ALIAS` | `cloudtrace` |
| `SIGNING_LINEAGE` | `lineage.bin` 的 base64 |

设置后，发布工作流会自动切换到“无签名构建 + apksigner 带 lineage 签名”。
四条缺任意一条时，工作流仍使用旧签名，不会误切换。

### 4. 发一个迁移版本

- `versionCode` / `versionName` 递增（例如 1.14.0 / 1140）
- 发布说明必须写明：
  - 本次更换了签名证书；
  - Android 9+ 可覆盖安装，Android 8.1 需卸载重装；
  - 应用内更新对旧版本不可用，请从官网或 Releases 手动下载。
- 打包后验证：

```bash
apksigner verify --print-certs --print-rotation-min-sdk-version app-release.apk
```

应能看到新证书、以及 rotation min SDK = 28。

### 5. 生成更新清单

轮换版的 manifest 仍然照常签（清单签名密钥与 APK 签名密钥是两套，互不影响）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/publish_update_manifest.ps1 -Tag v1.14.0
```

## 回滚

- 旧 keystore 永远不要删除；它就是“旧用户”的信任锚。
- 轮换后的版本不能再退回旧签名（Android 不允许降级签名）。
- 如果新签名有问题，只能继续用新签名发修复版，并保留旧版 APK 供用户手动回退（数据会丢）。

## 建议

在用户量还小、且能接受“手动重装一次”时执行最划算。执行前把上面两项前置条件确认完，
再跑脚本；否则建议维持现状——应用内更新链已经由签名清单保护，主要剩余风险是侧载伪装。