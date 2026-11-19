package com.zcshou.gogogo.run.update

import com.zcshou.gogogo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class UpdateFailure(val code: String, message: String) : Exception(message)

internal fun updateError(code: String, message: String): Nothing =
    throw UpdateFailure(code, message)

data class UpdateMirror(val id: String, val name: String, val url: String)

data class UpdateManifest(
    val channel: String,
    val revision: Long,
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val minSdk: Int,
    val abi: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
    val size: Long,
    val sha256: String,
    val mirrors: List<UpdateMirror>,
    val sourceSha: String,
    val buildId: String,
    val publishedAt: String,
    val envelope: String
) {
    val identity: String get() = "$channel:$versionCode:$sha256:$size:$abi"

    fun isInstallCandidate(currentCode: Int, sdk: Int): Boolean =
        minSdk <= sdk && versionCode > currentCode
}

/**
 * 更新清单验签器：schemaVersion=2 + ECDSA P-256 / SHA-256。
 *
 * payload 和 signature 都是标准 Base64；验签通过后才解析 payload，
 * 避免把未签名文本交给业务逻辑。
 */
object UpdateManifestVerifier {

    private val SEMVER = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")
    private val SHA256 = Regex("[a-f0-9]{64}")
    private val SOURCE_SHA = Regex("[a-f0-9]{40}")
    private val BUILD_ID = Regex("[0-9]{1,24}")
    private val PUBLISHED_AT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")
    private val MIRROR_ID = Regex("[a-z0-9-]{1,32}")
    private const val MANIFEST_LIMIT = 128 * 1024

    fun parseKeys(json: String): Map<String, PublicKey> = runCatching {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { keyId -> key(obj.getString(keyId)) }
    }.getOrElse {
        updateError("MANIFEST_KEY", "更新签名公钥不可用")
    }

    fun key(encoded: String): PublicKey {
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            ?: updateError("MANIFEST_KEY", "更新签名公钥格式无效")
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }.getOrElse {
            updateError("MANIFEST_KEY", "更新签名公钥格式无效")
        }
    }

    fun verify(
        text: String,
        keys: Map<String, PublicKey>,
        channel: String = UpdateConfig.CHANNEL
    ): UpdateManifest {
        if (text.toByteArray(Charsets.UTF_8).size > MANIFEST_LIMIT) {
            updateError("MANIFEST_SIZE", "更新清单过大")
        }
        try {
            val envelope = JSONObject(text)
            if (envelope.optInt("schemaVersion", -1) != 2) {
                updateError("MANIFEST_JSON", "更新清单版本不受支持")
            }
            val keyId = envelope.optString("keyId")
            val publicKey = keys[keyId] as? ECPublicKey
                ?: updateError("MANIFEST_SIGNATURE", "更新清单签名不受信任")
            if (publicKey.params.curve.field.fieldSize != 256 ||
                publicKey.params.order.bitLength() != 256
            ) {
                updateError("MANIFEST_SIGNATURE", "更新清单签名密钥强度不足")
            }

            val payloadBytes = runCatching {
                Base64.getDecoder().decode(envelope.getString("payload"))
            }.getOrNull() ?: updateError("MANIFEST_JSON", "更新清单格式无效")
            val signature = runCatching {
                Base64.getDecoder().decode(envelope.getString("signature"))
            }.getOrNull() ?: updateError("MANIFEST_JSON", "更新清单格式无效")

            val ok = runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(payloadBytes)
                    verify(signature)
                }
            }.getOrDefault(false)
            if (!ok) updateError("MANIFEST_SIGNATURE", "更新清单签名校验失败")

            val payload = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payloadBytes))
                    .toString()
            }.getOrElse {
                updateError("MANIFEST_JSON", "更新清单格式无效")
            }
            val p = JSONObject(payload)
            fun integer(name: String, min: Long, max: Long): Long {
                val value = p.get(name)
                if (value !is Number) updateError("MANIFEST_INVALID", "更新清单字段无效")
                return value.toLong().also {
                    if (it !in min..max) updateError("MANIFEST_INVALID", "更新清单字段无效")
                }
            }

            if (p.getString("channel") != channel) {
                updateError("MANIFEST_CHANNEL", "更新清单渠道不匹配")
            }
            if (p.getString("packageName") != BuildConfig.APPLICATION_ID) {
                updateError("MANIFEST_PACKAGE", "更新清单不是当前应用")
            }
            val versionCode = integer("versionCode", 1, Int.MAX_VALUE.toLong()).toInt()
            val versionName = p.getString("versionName")
            if (!SEMVER.matches(versionName)) {
                updateError("MANIFEST_INVALID", "更新清单版本号格式无效")
            }
            val minSdk = integer("minSdk", 21, 1000).toInt()
            val abi = p.getString("abi")
            if (abi != "arm64-v8a") {
                updateError("MANIFEST_ABI", "更新清单架构不受支持")
            }
            val notes = p.getString("releaseNotes")
            if (notes.length > 20_000) {
                updateError("MANIFEST_INVALID", "更新说明过长")
            }
            if (p.get("forceUpdate") !is Boolean) {
                updateError("MANIFEST_INVALID", "更新清单字段无效")
            }
            val size = integer("size", 1, 512L * 1024 * 1024)
            val sha256 = p.getString("sha256")
            if (!SHA256.matches(sha256)) {
                updateError("MANIFEST_INVALID", "更新包摘要格式无效")
            }
            val sourceSha = p.getString("sourceSha")
            if (!SOURCE_SHA.matches(sourceSha)) {
                updateError("MANIFEST_INVALID", "更新源提交格式无效")
            }
            val buildId = p.getString("buildId")
            if (!BUILD_ID.matches(buildId)) {
                updateError("MANIFEST_INVALID", "更新构建号格式无效")
            }
            val publishedAt = p.getString("publishedAt")
            if (!PUBLISHED_AT.matches(publishedAt)) {
                updateError("MANIFEST_INVALID", "发布时间格式无效")
            }

            val mirrorsJson: JSONArray = p.getJSONArray("mirrors")
            if (mirrorsJson.length() !in 1..8) {
                updateError("MANIFEST_INVALID", "更新线路数量无效")
            }
            val mirrorIds = mutableSetOf<String>()
            val mirrors = (0 until mirrorsJson.length()).map { index ->
                val item = mirrorsJson.getJSONObject(index)
                val id = item.getString("id")
                val name = item.getString("name")
                val url = item.getString("url")
                if (!MIRROR_ID.matches(id) || !mirrorIds.add(id) ||
                    name.length !in 1..40 || !UpdateUrls.isAllowed(url)
                ) {
                    updateError("MANIFEST_URL", "更新下载线路不受支持")
                }
                UpdateMirror(id, name, url)
            }

            return UpdateManifest(
                channel = channel,
                revision = integer("revision", 1, Long.MAX_VALUE),
                packageName = p.getString("packageName"),
                versionCode = versionCode,
                versionName = versionName,
                minSdk = minSdk,
                abi = abi,
                releaseNotes = notes,
                forceUpdate = p.getBoolean("forceUpdate"),
                size = size,
                sha256 = sha256,
                mirrors = mirrors,
                sourceSha = sourceSha,
                buildId = buildId,
                publishedAt = publishedAt,
                envelope = text
            )
        } catch (e: UpdateFailure) {
            throw e
        } catch (_: Exception) {
            updateError("MANIFEST_INVALID", "更新清单无效或不兼容")
        }
    }

    /** 双源结果取版本号最高、同版本取 revision 更新的一份。 */
    fun choose(candidates: List<UpdateManifest>): UpdateManifest? =
        candidates.maxWithOrNull(
            compareBy<UpdateManifest> { it.versionCode }.thenBy { it.revision }
        )
}
