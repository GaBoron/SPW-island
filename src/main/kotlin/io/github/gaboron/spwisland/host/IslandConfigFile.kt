// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package io.github.gaboron.spwisland.host

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

    override fun reload(): Boolean = runCatching { reload(Files.readAllBytes(path)) }.getOrDefault(false)
    fun reload(bytes: ByteArray): Boolean = runCatching {
        if (bytes.isEmpty()) return false
        val parsed = gson.fromJson(bytes.toString(Charsets.UTF_8), JsonObject::class.java) ?: return false
        values = parsed
        loaded = bytes
        true
    }.getOrDefault(false)

    override fun save(): Boolean = runCatching {
        Files.createDirectories(path.parent)
        // Do not replace an edit which arrived since reload. The caller retries from the new file.
        val current = if (Files.exists(path)) Files.readAllBytes(path) else null
        if (loaded == null && current != null || loaded != null && (current == null || !loaded!!.contentEquals(current)))
            return false
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
