package com.zcshou.gogogo.run

/**
 * 品牌单一配置源：应用展示名统一取 `strings.xml/app_name`，
 * 口令协议前缀集中在这里，后续全局更名只需改一处。
 */
object BrandConfig {

    /** 路线分享口令协议名（UI 展示用）。 */
    const val SHARE_SCHEME = "CLOUDTRACE"

    /** 路线分享口令前缀（解析/生成共用）。 */
    const val SHARE_PREFIX = "$SHARE_SCHEME://"

    /** 更名前的旧协议前缀，仅用于兼容已分享出去的口令。 */
    const val LEGACY_SCHEME = "DEEPRUN"
    const val LEGACY_PREFIX = "$LEGACY_SCHEME://"
}
