// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

import io.github.gaboron.spwisland.diagnostics.IslandLog

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Decode exactly the bytes we read, never the host's shared mutable settings cache. */
internal class IslandConfigFile(private val path: Path) : ConfigHelper {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private var values = JsonObject()
    private var loaded: ByteArray? = null

    override fun getConfigPath() = path
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T {
        val element = values[key]?.takeUnless { it.isJsonNull } ?: return defaultValue
        val value: Any = if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asDouble
                else -> primitive.asString
            }
        } else return defaultValue
        return value as T
    }
    override fun set(key: String, value: Any) { values.add(key, gson.toJsonTree(value)) }

    override fun reload(): Boolean = IslandLog.attempt("IslandConfigFile", "reload.attempt1") { reload(Files.readAllBytes(path)) }.getOrDefault(false)
    fun reload(bytes: ByteArray): Boolean = IslandLog.attempt("IslandConfigFile", "reload.attempt2") {
        if (bytes.isEmpty()) {
            IslandLog.state("settings", "config_read", "empty_retrying"); return false
        }
        val parsed = gson.fromJson(bytes.toString(Charsets.UTF_8), JsonObject::class.java) ?: run {
            IslandLog.state("settings", "config_read", "incomplete_retrying"); return false
        }
        values = parsed
        loaded = bytes
        IslandLog.state("settings", "config_read", "valid")
        true
    }.getOrDefault(false)

    override fun save(): Boolean = IslandLog.attempt("IslandConfigFile", "save.attempt3") {
        Files.createDirectories(path.parent)
        // Do not replace an edit which arrived since reload. The caller retries from the new file.
        val current = if (Files.exists(path)) Files.readAllBytes(path) else null
        if (loaded == null && current != null || loaded != null && (current == null || !loaded!!.contentEquals(current))) {
            IslandLog.warn("settings", "save.concurrent_edit_retry")
            return false
        }
        val bytes = gson.toJson(values).toByteArray(Charsets.UTF_8)
        val temporary = Files.createTempFile(path.parent, ".island-", ".json")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            loaded = bytes
        } finally { Files.deleteIfExists(temporary) }
        true
    }.getOrDefault(false)
}
