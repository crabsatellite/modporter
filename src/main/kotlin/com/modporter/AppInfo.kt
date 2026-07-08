package com.modporter

import java.util.Properties

object AppInfo {
    const val name: String = "ModPorter"

    val version: String by lazy {
        resourceVersion()
            ?: packageVersion()
            ?: systemVersion()
            ?: "dev"
    }

    val userAgent: String
        get() = "modporter/$version"

    private fun resourceVersion(): String? {
        val stream = AppInfo::class.java.getResourceAsStream("/modporter-version.properties") ?: return null
        return stream.use {
            val properties = Properties()
            properties.load(it)
            properties.getProperty("version")?.validVersionValue()
        }
    }

    private fun packageVersion(): String? =
        AppInfo::class.java.`package`?.implementationVersion?.validVersionValue()

    private fun systemVersion(): String? =
        System.getProperty("modporter.version")?.validVersionValue()

    private fun String.validVersionValue(): String? {
        val value = trim()
        return value.takeIf { it.isNotBlank() && '$' !in it }
    }
}
