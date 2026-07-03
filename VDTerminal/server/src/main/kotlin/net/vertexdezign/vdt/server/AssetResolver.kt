package net.vertexdezign.vdt.server

import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class ResolvedAsset(val bytes: ByteArray, val path: Path)

/**
 * Resolves a map/PDA asset path to bytes. Port of `assets.go`, plus Proton drive-letter mapping:
 *  0. if the path is a Windows drive-letter path (e.g. `S:/common/…`, as the game writes under
 *     Proton on Linux), translate the drive via the Proton prefix's `dosdevices/<letter>:` symlink;
 *  1. absolute path, or gameDir-relative;
 *  2. if the path points inside `mods/<mod>/…`, try `mods/<mod>.zip` (by entry name);
 *  3. then the unpacked `mods/<mod>/…` folder;
 *  4. finally the resolved path as-is.
 */
object AssetResolver {
    private val driveLetterPath = Regex("""^([A-Za-z]):[\\/](.*)$""")

    fun resolve(gameDir: Path, filename: String): ResolvedAsset? {
        val resolved: Path = translateDrivePath(filename, gameDir)
            ?: Path(filename).let { if (it.isAbsolute) it else gameDir.resolve(filename) }
        val resolvedStr = resolved.toString()

        val marker = markerIn(resolvedStr)
        if (marker != null) {
            val modPart = resolvedStr.substringAfter(marker)
            val modName = modPart.substringBefore(File.separatorChar).substringBefore('/')
            if (modName.isNotEmpty() && modPart.length > modName.length) {
                val restOfPath = modPart.substring(modName.length + 1)
                val modsDir = gameDir.resolve("mods")
                val zipPath = modsDir.resolve("$modName.zip")
                val entryName = restOfPath.replace('\\', '/')

                if (zipPath.exists()) {
                    runCatching {
                        ZipFile(zipPath.toFile()).use { zip ->
                            val entry = zip.entries().asSequence()
                                .firstOrNull { it.name.replace('\\', '/') == entryName }
                            if (entry != null) {
                                return ResolvedAsset(zip.getInputStream(entry).use { it.readBytes() }, resolved)
                            }
                        }
                    }
                }

                val folderFile = modsDir.resolve(modName).resolve(restOfPath)
                if (folderFile.exists()) return ResolvedAsset(folderFile.readBytes(), folderFile)
            }
        }

        if (resolved.exists()) return ResolvedAsset(resolved.readBytes(), resolved)
        return null
    }

    private fun markerIn(path: String): String? = when {
        path.contains("mods${File.separatorChar}") -> "mods${File.separatorChar}"
        path.contains("mods/") -> "mods/"
        path.contains("mods\\") -> "mods\\"
        else -> null
    }

    /**
     * Translates a Windows drive-letter path (as the game emits under Proton, e.g.
     * `S:/common/Farming Simulator 25/...`) into a real Linux path by resolving the drive through
     * the Proton prefix's `dosdevices/<letter>:` symlink. Returns null if [filename] isn't a
     * drive-letter path or the prefix/drive can't be found (e.g. native Windows, where the path
     * is already valid and handled by the normal branch).
     */
    private fun translateDrivePath(filename: String, gameDir: Path): Path? {
        val match = driveLetterPath.matchEntire(filename) ?: return null
        val letter = match.groupValues[1].lowercase()
        val remainder = match.groupValues[2].replace('\\', '/')

        val prefix = findProtonPrefix(gameDir) ?: return null
        val driveLink = prefix.resolve("dosdevices").resolve("$letter:")
        if (!driveLink.exists()) return null

        val driveRoot = runCatching { driveLink.toRealPath() }.getOrNull() ?: return null
        return if (remainder.isEmpty()) driveRoot else driveRoot.resolve(remainder)
    }

    /** Walks up from [gameDir] to the Proton prefix (the ancestor containing `dosdevices`). */
    private fun findProtonPrefix(gameDir: Path): Path? {
        var dir: Path? = gameDir
        while (dir != null) {
            if (dir.resolve("dosdevices").exists()) return dir
            dir = dir.parent
        }
        return null
    }
}
