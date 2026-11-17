// SPDX-License-Identifier: GPL-3.0-only
package io.github.gaboron.spwisland.ui

import io.github.gaboron.spwisland.core.LyricFontWeight
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.awt.font.TextAttribute
import kotlin.math.abs

/** Creates the shared MiSans UI/lyric font, with optional lyric-only custom fonts. */
internal object SystemUiFont {
    private data class InstalledFace(val font: Font, val weight: Int)

    private val installedFonts by lazy {
        GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts.toList()
    }
    private val installedFamilies = ConcurrentHashMap<String, List<InstalledFace>>()
    private data class GlyphKey(val text: String, val style: Int)
    private val glyphCandidates = ConcurrentHashMap<Int, List<Font>>()
    private val glyphFaces = object : LinkedHashMap<GlyphKey, Font?>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphKey, Font?>) = size > 512
    }
    fun derive(style: Int, size: Float): Font = bundledMiSans.deriveFont(style, size)

    fun lyric(family: String, weight: LyricFontWeight, size: Float): Font {
        val requested = normalizeName(family)
        if (requested.isEmpty()) return lyricFallback(weight, size)
        val direct = facesFor(requested)
        if (direct.isNotEmpty()) return selectFace(direct, weight, size)
        exactFace(requested)?.let { return it.deriveFont(size) }
        fileFace(requested, weight)?.let { return it.deriveFont(size) }
        val fallbackFaces = strippedCandidates(requested).firstNotNullOfOrNull { candidate ->
            facesFor(candidate).takeIf { it.isNotEmpty() }
        }
        return fallbackFaces?.let { selectFace(it, weight, size) } ?: lyricFallback(weight, size)
    }

    private fun selectFace(faces: List<InstalledFace>, weight: LyricFontWeight, size: Float): Font {
        val target = weight.storageName.toInt()
        val face = faces.minWith(compareBy<InstalledFace>(
            { abs(it.weight - target) },
            { if (target >= 500) -it.weight else it.weight }
        ))
        return face.font.deriveFont(size)
    }

    private fun facesFor(requested: String): List<InstalledFace> =
        installedFamilies.computeIfAbsent(requested) { installedFacesFor(requested) }

    private fun installedFacesFor(requested: String): List<InstalledFace> =
        installedFonts.asSequence()
            .filter { belongsToFamily(it, requested) && !it.isItalicFace }
            .map { InstalledFace(it, it.inferredWeight) }
            .distinctBy { it.font.psName }
            .toList()

    /** Progressively drops trailing style/weight words, tolerating GDI-style variants Java groups differently. */
    private fun strippedCandidates(requested: String): Sequence<String> = sequence {
        var current = requested
        while (true) {
            val trimmed = stripTrailingToken(current) ?: break
            if (trimmed == current) break
            current = trimmed
            yield(current)
        }
    }

    private fun stripTrailingToken(name: String): String? {
        val index = name.lastIndexOf(' ')
        if (index <= 0) return null
        return name.substring(0, index).takeIf { name.substring(index + 1) in removableTokens }
    }

    /** Full face names such as "Arial Bold Italic" resolve exactly, including italic faces. */
    private fun exactFace(requested: String): Font? = exactFaces[requested]

    private val exactFaces by lazy {
        buildMap {
            installedFonts.forEach { font ->
                for (locale in arrayOf(Locale.ROOT, Locale.getDefault())) {
                    val name = normalizeName(font.getFontName(locale))
                    if (name !in this) put(name, font)
                }
                val postScript = normalizeName(font.psName)
                if (postScript !in this) put(postScript, font)
            }
        }
    }

    /** AWT's font registry hides many installed faces; load the real font file instead. */
    private fun fileFace(requested: String, weight: LyricFontWeight): Font? {
        val path = requestedFontFile(requested) ?: return null
        val faces = fontsFromFile(path)
        if (faces.isEmpty()) return null
        faces.firstOrNull { it.matchesRequest(requested) }?.let { return it }
        val target = weight.storageName.toInt()
        return faces.minWithOrNull(compareBy<Font>(
            { abs(it.inferredWeight - target) },
            { if (target >= 500) -it.inferredWeight else it.inferredWeight }
        ))
    }

    private fun requestedFontFile(requested: String): Path? {
        if (!Platform.isWindows()) return null
        return windowsFontFiles[requested]
            ?: strippedCandidates(requested).firstNotNullOfOrNull { windowsFontFiles[it] }
    }

    private fun fontsFromFile(path: Path): List<Font> =
        fontFiles.computeIfAbsent(path) { file ->
            runCatching { Font.createFonts(file.toFile()).toList() }.getOrDefault(emptyList())
        }

    private fun Font.matchesRequest(requested: String): Boolean =
        normalizeName(getFontName(Locale.ROOT)) == requested ||
            normalizeName(getFontName(Locale.getDefault())) == requested ||
            normalizeName(psName) == requested

    private val fontFiles = ConcurrentHashMap<Path, List<Font>>()

    private val windowsFontFiles: Map<String, Path> by lazy {
        if (!Platform.isWindows()) emptyMap() else windowsRegistryFontFiles()
    }

    private fun windowsRegistryFontFiles(): Map<String, Path> = buildMap {
        val keys = listOf(
            WinReg.HKEY_LOCAL_MACHINE to "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts",
            WinReg.HKEY_CURRENT_USER to "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
        )
        for ((root, key) in keys) {
            val values = runCatching { Advapi32Util.registryGetValues(root, key) }.getOrNull() ?: continue
            values.forEach { (rawName, rawValue) ->
                val file = rawValue as? String ?: return@forEach
                val path = resolveFontFile(file) ?: return@forEach
                registryFontNames(rawName).forEach { name -> putIfAbsent(normalizeName(name), path) }
            }
        }
    }

    private fun registryFontNames(rawName: String): List<String> =
        rawName.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
            .split(" & ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun resolveFontFile(file: String): Path? {
        val direct = runCatching { Path.of(file) }.getOrNull()
        if (direct != null && direct.isAbsolute && Files.isRegularFile(direct)) return direct
        val candidates = listOfNotNull(
            runCatching { Path.of(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts", file) }.getOrNull(),
            System.getenv("LOCALAPPDATA")?.let { root ->
                runCatching { Path.of(root, "Microsoft", "Windows", "Fonts", file) }.getOrNull()
            }
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private val bundledMiSans by lazy {
        val resource = "/fonts/MiSansVF.ttf"
        val stream = checkNotNull(SystemUiFont::class.java.getResourceAsStream(resource)) {
            "内置字体资源缺失：$resource"
        }
        stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }

    fun lyricFallback(weight: LyricFontWeight, size: Float): Font {
        val anchors = listOf(
            100 to TextAttribute.WEIGHT_EXTRA_LIGHT,
            300 to TextAttribute.WEIGHT_LIGHT,
            350 to TextAttribute.WEIGHT_DEMILIGHT,
            400 to TextAttribute.WEIGHT_REGULAR,
            500 to TextAttribute.WEIGHT_MEDIUM,
            700 to TextAttribute.WEIGHT_BOLD,
            900 to TextAttribute.WEIGHT_HEAVY
        )
        val upper = anchors.indexOfFirst { it.first >= weight.value }.coerceAtLeast(0)
        val high = anchors[upper]
        val low = anchors[(upper - 1).coerceAtLeast(0)]
        val fraction = if (high.first == low.first) 0f else
            (weight.value - low.first).toFloat() / (high.first - low.first)
        val awtWeight = low.second + (high.second - low.second) * fraction
        return bundledMiSans.deriveFont(mapOf(TextAttribute.WEIGHT to awtWeight,
            TextAttribute.SIZE to size))
    }

    fun glyphFallback(reference: Font): Font {
        val style = if (reference.inferredWeight >= 600) Font.BOLD else Font.PLAIN
        return Font(Font.SANS_SERIF, style, reference.size.coerceAtLeast(1)).deriveFont(reference.size2D)
    }

    /** Logical fonts omit some installed script fonts; keep each grapheme in a single physical face. */
    fun installedGlyphFallback(text: String, reference: Font): Font? {
        val style = reference.style
        val key = GlyphKey(text, style)
        synchronized(glyphFaces) {
            // Cache unsupported clusters too, so a missing font never causes a scan on every line.
            if (glyphFaces.containsKey(key)) return glyphFaces[key]?.deriveFont(style, reference.size2D)
        }
        val candidates = glyphCandidates.computeIfAbsent(style) {
            val weight = if (style and Font.BOLD != 0) 700 else 400
            val italic = style and Font.ITALIC != 0
            installedFonts.sortedWith(compareBy<Font>(
                { if (it.isItalicFace == italic) 0 else 1 },
                { abs(it.inferredWeight - weight) }
            ))
        }
        val face = candidates.firstOrNull { it.canDisplayUpTo(text) < 0 }
        synchronized(glyphFaces) { glyphFaces[key] = face }
        return face?.deriveFont(style, reference.size2D)
    }

    private fun belongsToFamily(font: Font, requested: String): Boolean {
        return sequenceOf(Locale.ROOT, Locale.getDefault()).any { locale ->
            val fontFamily = normalizeName(font.getFamily(locale))
            val fontName = normalizeName(font.getFontName(locale))
            if (fontFamily == requested || fontName == requested) return@any true
            val suffix = fontFamily.removePrefix("$requested ")
            suffix != fontFamily && suffix in weightFamilySuffixes
        }
    }

    private val Font.isItalicFace: Boolean
        get() {
            val name = normalizeName("${getFontName(Locale.ROOT)} $psName")
            return name.containsWord("italic") || name.containsWord("oblique")
        }

    private val Font.inferredWeight: Int
        get() {
            val name = normalizeName("${getFamily(Locale.ROOT)} ${getFontName(Locale.ROOT)} $psName")
            return weightNames.firstOrNull { (marker, _) -> name.containsWord(marker) }?.second ?: 400
        }

    private fun String.containsWord(marker: String): Boolean =
        this == marker || startsWith("$marker ") || endsWith(" $marker") || contains(" $marker ")

    private fun normalizeName(name: String): String = name
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[-_]+"), " ")
        .replace(Regex("\\s+"), " ")

    private val weightNames = listOf(
        "extra black" to 950, "ultra black" to 950,
        "extra bold" to 800, "ultra bold" to 800, "extrabold" to 800, "ultrabold" to 800,
        "semi bold" to 600, "demi bold" to 600, "semibold" to 600, "demibold" to 600,
        "extra light" to 200, "ultra light" to 200, "extralight" to 200, "ultralight" to 200,
        "semi light" to 350, "demi light" to 350, "semilight" to 350, "demilight" to 350,
        "hairline" to 100, "thin" to 100,
        "light" to 300,
        "medium" to 500,
        "black" to 900, "heavy" to 900,
        "bold" to 700,
        "regular" to 400, "normal" to 400, "book" to 400, "roman" to 400
    )

    private val weightFamilySuffixes = weightNames.mapTo(mutableSetOf()) { it.first }

    /** Trailing tokens stripped when matching GDI families that Java exposes under a base name. */
    private val removableTokens = weightFamilySuffixes + "ui"

}
