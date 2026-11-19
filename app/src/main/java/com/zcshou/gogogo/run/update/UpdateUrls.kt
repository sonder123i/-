package com.zcshou.gogogo.run.update

import java.net.URI

/**
 * 更新 URL 校验（服务端请求约束）：
 * - 只允许 http/https，实际更新链路只放行 https；
 * - 拒绝 localhost、环回、私网、链路本地和保留地址；
 * - 不允许账号密码、fragment 和非 443 端口。
 *
 * 清单镜像 URL 虽然由签名保护，仍然逐条校验，避免发版机误填内网地址。
 */
object UpdateUrls {

    fun isAllowed(raw: String, hosts: Set<String> = UpdateConfig.allowedHosts): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null || uri.fragment != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = normalizeHost(uri.host) ?: return false
        if (host.isEmpty() || host !in hosts) return false
        if (isPrivateOrReserved(host)) return false
        return true
    }

    fun hostOf(raw: String): String? = runCatching {
        normalizeHost(URI(raw.trim()).host)
    }.getOrNull()

    private fun normalizeHost(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.isEmpty()) return null
        // URI.host 对 IPv6 字面量通常保留方括号；这里统一去掉。
        val host = value.removePrefix("[").removeSuffix("]")
        if (host.contains('%')) return null // zone id / scope id 不允许
        return host
    }

    private fun isPrivateOrReserved(host: String): Boolean {
        if (host == "localhost" || host == "localhost.localdomain") return true
        if (host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") ||
            host.endsWith(".home") || host.endsWith(".home.arpa")
        ) return true

        // 任何 IPv6 字面量都拒绝：更新源只允许域名，避免 [::1]/ULA/链路本地绕过。
        if (host.contains(':')) return true

        // 纯数字（含点分十进制）只按 IPv4 处理；非法数字串直接拒绝。
        if (host.all { it.isDigit() || it == '.' }) {
            return isPrivateIpv4(host) || !isValidIpv4(host)
        }

        // 形如 127.1 之类的短写也按 IPv4 处理。
        val ipv4Like = host.matches(Regex("""\d{1,3}(\.\d{1,3}){1,3}"""))
        if (ipv4Like) return isPrivateIpv4(host) || !isValidIpv4(host)

        return false
    }

    private fun isValidIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return true
        val a = parts[0]
        val b = parts[1]
        val c = parts[2]
        return when {
            a == 0 -> true                         // 0.0.0.0/8
            a == 10 -> true                        // 10/8
            a == 127 -> true                       // loopback
            a == 169 && b == 254 -> true           // link-local
            a == 172 && b in 16..31 -> true        // 172.16/12
            a == 192 && b == 0 && c == 0 -> true   // 192.0.0/24
            a == 192 && b == 0 && c == 2 -> true   // TEST-NET-1
            a == 192 && b == 168 -> true           // 192.168/16
            a == 198 && b in 18..19 -> true        // benchmarking
            a == 198 && b == 51 && c == 100 -> true
            a == 203 && b == 0 && c == 113 -> true // TEST-NET-3
            a == 100 && b in 64..127 -> true       // CGNAT
            a >= 224 -> true                       // multicast + reserved
            else -> false
        }
    }
}
