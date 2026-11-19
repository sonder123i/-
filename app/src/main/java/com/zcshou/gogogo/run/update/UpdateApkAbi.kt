package com.zcshou.gogogo.run.update

import java.io.File
import java.util.zip.ZipFile

/** 校验 APK 内确实带了发布时声明的 arm64 native lib。 */
internal object UpdateApkAbi {

    fun verify(file: File, expectedAbi: String) {
        val abis = runCatching {
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .map { it.name.split('/').getOrNull(1).orEmpty() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        }.getOrElse {
            updateError("APK_ABI", "安装包架构信息无效")
        }
        if (expectedAbi !in abis) {
            updateError("APK_ABI", "安装包架构与更新清单不一致")
        }
    }
}
