// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.extension

import kotlinx.serialization.json.*

/**
 * Information about a loaded extension.
 */
data class ExtensionInfo(
    val name: String,
    val version: String,
    val minCoreVersion: String,
    val maxCoreVersion: String = "99.99.99",
    val apiVersion: Int = 1,
    val packageName: String = ""
)

/**
 * A loaded extension ready for use.
 */
data class LoadedExtension(
    val info: ExtensionInfo,
    val resolvedApiVersion: Int
)

/**
 * Parses extension manifest JSON.
 */
class ManifestParser {
    fun parse(json: String): ExtensionInfo {
        return try {
            val element = Json.parseToJsonElement(json) as JsonObject
            ExtensionInfo(
                name = element["name"]?.let { (it as JsonPrimitive).content } ?: "unknown",
                version = element["version"]?.let { (it as JsonPrimitive).content } ?: "0.0.0",
                minCoreVersion = element["minCoreVersion"]?.let { (it as JsonPrimitive).content } ?: "0.0.0",
                maxCoreVersion = element["maxCoreVersion"]?.let { (it as JsonPrimitive).content } ?: "99.99.99",
                apiVersion = element["apiVersion"]?.let { (it as JsonPrimitive).int } ?: 1,
                packageName = element["packageName"]?.let { (it as JsonPrimitive).content } ?: ""
            )
        } catch (e: Exception) {
            ExtensionInfo("unknown", "0.0.0", "0.0.0")
        }
    }
}

/**
 * Compares version strings using semantic versioning.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    companion object {
        fun parse(v: String): Version {
            val parts = v.split(".").map { it.toIntOrNull() ?: 0 }
            return Version(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }

    override fun compareTo(other: Version): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}
