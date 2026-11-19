package com.zcshou.gogogo.run.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlsTest {

    @Test
    fun acceptsConfiguredReleaseHosts() {
        assertTrue(UpdateUrls.isAllowed("https://github.com/o/r/releases/download/v1/app.apk"))
        assertTrue(UpdateUrls.isAllowed("https://gitee.com/o/r/releases/download/v1/app.apk"))
        assertTrue(UpdateUrls.isAllowed("https://objects.githubusercontent.com/x/app.apk"))
        assertTrue(UpdateUrls.isAllowed(UpdateConfig.sources.first()))
    }

    @Test
    fun rejectsLoopbackAndPrivateAddresses() {
        listOf(
            "https://localhost/app.apk",
            "https://127.0.0.1/app.apk",
            "https://10.0.0.1/app.apk",
            "https://172.16.0.1/app.apk",
            "https://192.168.1.1/app.apk",
            "https://169.254.1.1/app.apk",
            "https://100.64.0.1/app.apk",
            "https://[::1]/app.apk",
            "https://[fe80::1]/app.apk",
            "https://0.0.0.0/app.apk"
        ).forEach { url ->
            assertFalse(url, UpdateUrls.isAllowed(url))
        }
    }

    @Test
    fun rejectsUnsafeSchemesPortsAndUserInfo() {
        listOf(
            "http://github.com/app.apk",
            "https://github.com:8443/app.apk",
            "https://user@github.com/app.apk",
            "https://github.com/app.apk#fragment",
            "ftp://github.com/app.apk"
        ).forEach { url ->
            assertFalse(url, UpdateUrls.isAllowed(url))
        }
    }

    @Test
    fun rejectsHostsOutsideAllowlist() {
        assertFalse(UpdateUrls.isAllowed("https://example.com/app.apk"))
        assertFalse(UpdateUrls.isAllowed("https://evilgithub.com/app.apk"))
    }
}
