package com.zcshou.gogogo.run.update

import com.zcshou.gogogo.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdateManifestVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val keyId = "test-key"
    private val keys = mapOf(keyId to keyPair.public)

    private fun payload(
        packageName: String = BuildConfig.APPLICATION_ID,
        versionCode: Int = BuildConfig.VERSION_CODE + 1,
        mirrorUrl: String = "https://github.com/example/app/releases/download/v1/app.apk"
    ): JSONObject = JSONObject().apply {
        put("channel", "stable")
        put("revision", 100L)
        put("packageName", packageName)
        put("versionCode", versionCode)
        put("versionName", "1.13.0")
        put("minSdk", 27)
        put("abi", "arm64-v8a")
        put("releaseNotes", "修复若干问题\n新增更新功能")
        put("forceUpdate", false)
        put("size", 12_345L)
        put("sha256", "a".repeat(64))
        put("sourceSha", "b".repeat(40))
        put("buildId", "1234567890")
        put("publishedAt", "2026-11-19T00:00:00Z")
        put(
            "mirrors",
            JSONArray().put(
                JSONObject().apply {
                    put("id", "gh")
                    put("name", "GitHub")
                    put("url", mirrorUrl)
                }
            )
        )
    }

    private fun envelope(payload: JSONObject, sign: Boolean = true): String {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(bytes)
        val signature = if (sign) signer.sign() else bytes
        return JSONObject().apply {
            put("schemaVersion", 2)
            put("keyId", keyId)
            put("payload", Base64.getEncoder().encodeToString(bytes))
            put("signature", Base64.getEncoder().encodeToString(signature))
        }.toString()
    }

    @Test
    fun signedManifestRoundTrips() {
        val manifest = UpdateManifestVerifier.verify(
            envelope(payload()), keys, "stable"
        )
        assertEquals(BuildConfig.APPLICATION_ID, manifest.packageName)
        assertEquals("1.13.0", manifest.versionName)
        assertEquals("arm64-v8a", manifest.abi)
        assertEquals(1, manifest.mirrors.size)
        assertTrue(manifest.isInstallCandidate(BuildConfig.VERSION_CODE, 33))
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val original = envelope(payload())
        val obj = JSONObject(original)
        val decoded = String(
            Base64.getDecoder().decode(obj.getString("payload")),
            Charsets.UTF_8
        )
        val tampered = JSONObject(decoded).put("versionName", "9.9.9")
        obj.put(
            "payload",
            Base64.getEncoder().encodeToString(
                tampered.toString().toByteArray(Charsets.UTF_8)
            )
        )
        assertFailure("MANIFEST_SIGNATURE") {
            UpdateManifestVerifier.verify(obj.toString(), keys, "stable")
        }
    }

    @Test
    fun wrongPackageIsRejected() {
        assertFailure("MANIFEST_PACKAGE") {
            UpdateManifestVerifier.verify(
                envelope(payload(packageName = "com.other.app")), keys, "stable"
            )
        }
    }

    @Test
    fun privateMirrorIsRejected() {
        assertFailure("MANIFEST_URL") {
            UpdateManifestVerifier.verify(
                envelope(payload(mirrorUrl = "https://127.0.0.1/app.apk")),
                keys,
                "stable"
            )
        }
    }

    @Test
    fun httpMirrorIsRejected() {
        assertFailure("MANIFEST_URL") {
            UpdateManifestVerifier.verify(
                envelope(payload(mirrorUrl = "http://github.com/app.apk")),
                keys,
                "stable"
            )
        }
    }

    @Test
    fun olderVersionIsNotInstallCandidate() {
        val manifest = UpdateManifestVerifier.verify(
            envelope(payload(versionCode = BuildConfig.VERSION_CODE)), keys, "stable"
        )
        assertFalse(manifest.isInstallCandidate(BuildConfig.VERSION_CODE, 33))
    }

    @Test
    fun unknownSignatureKeyIsRejected() {
        assertFailure("MANIFEST_SIGNATURE") {
            UpdateManifestVerifier.verify(
                envelope(payload()), emptyMap(), "stable"
            )
        }
    }

    @Test
    fun highestVersionWinsWhenBothSourcesReturn() {
        val low = UpdateManifestVerifier.verify(
            envelope(payload(versionCode = BuildConfig.VERSION_CODE + 1)), keys, "stable"
        )
        val high = UpdateManifestVerifier.verify(
            envelope(payload(versionCode = BuildConfig.VERSION_CODE + 2)), keys, "stable"
        )
        assertNotNull(UpdateManifestVerifier.choose(listOf(low, high)))
        assertEquals(
            BuildConfig.VERSION_CODE + 2,
            UpdateManifestVerifier.choose(listOf(low, high))!!.versionCode
        )
    }

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected UpdateFailure($code)")
        } catch (e: UpdateFailure) {
            assertEquals(code, e.code)
        }
    }
}
