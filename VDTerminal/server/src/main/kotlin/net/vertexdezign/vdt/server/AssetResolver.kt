package net.vertexdezign.vdt.server

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * A found asset, and where it was found: [path] is the file that was opened, which for a zipped mod
 * is the archive rather than the asset, and [entry] then names the entry inside it.
 */
class ResolvedAsset(val bytes: ByteArray, val path: Path, val entry: String? = null) {
  /** One line naming the actual origin, for a log or an error body. */
  val source: String get() = if (entry == null) path.toString() else "$path!$entry"
}

/**
 * The outcome of a lookup: the asset, plus every place that was looked at on the way.
 *
 * [tried] exists because this is the failure the user cannot debug: the path was chosen by the game,
 * on the far side of a file, and "Image not found" on its own says nothing about whether we looked
 * for the zip, where we looked for it, or whether we ever got as far as the game folder. It is
 * logged *and* returned to the browser, so a screenshot of an empty map carries its own diagnosis.
 */
class AssetLookup(val asset: ResolvedAsset?, val tried: List<String>)

/**
 * Resolves a map/PDA asset path to bytes:
 *  0. if the path is a Windows drive-letter path (e.g. `S:/common/…`, as the game writes under
 *     Proton on Linux), translate the drive via the Proton prefix's `dosdevices/<letter>:` symlink;
 *  1. look the path up: the file itself, or — since the engine reports a zipped mod's assets as if
 *     the zip were a folder — an entry in the `.zip` beside one of its ancestors;
 *  2. failing that, if the path runs through a `mods/` folder, re-anchor it onto `<gameDir>/mods`
 *     and look that up the same way.
 *
 * Step 2 exists only for a path we can't reach as reported: a relative one, or an absolute one from
 * a game folder that isn't the one this server is pointed at.
 */
object AssetResolver {
  private val log = LoggerFactory.getLogger(AssetResolver::class.java)

  private val driveLetterPath = Regex("""^([A-Za-z]):[\\/](.*)$""")

  fun resolve(gameDir: Path, filename: String): ResolvedAsset? = lookup(gameDir, filename).asset

  /** [resolve], keeping the trail of everything it looked at. */
  fun lookup(gameDir: Path, filename: String): AssetLookup {
    val tried = mutableListOf<String>()
    val translated = translateDrivePath(filename, gameDir)
    if (translated != null) log.debug("drive-letter path {} translated to {}", filename, translated)
    val resolved: Path =
      translated ?: Path(filename).let { if (it.isAbsolute) it else gameDir.resolve(filename) }

    lookUp(resolved, tried)?.let { return AssetLookup(it, tried) }

    val reanchored = reanchorInGameMods(gameDir, resolved.toString())
    if (reanchored == null) {
      log.debug("{} is not under a mods/ folder, so there is nothing to re-anchor onto {}", resolved, gameDir)
      return AssetLookup(null, tried)
    }
    log.debug("re-anchoring {} onto this install as {}", resolved, reanchored)
    return AssetLookup(lookUp(reanchored, tried), tried)
  }

  /** The file itself if it is one, else the zipped mod it pretends to be a folder in. */
  private fun lookUp(path: Path, tried: MutableList<String>): ResolvedAsset? {
    // A real file at the path wins: nothing else can be more right than the path itself.
    tried += path.toString()
    if (path.isRegularFile()) {
      log.debug("found {} as a plain file", path)
      return ResolvedAsset(path.readBytes(), path)
    }
    log.debug("no plain file at {}; looking for a zipped mod around it", path)
    return zipAncestorAsset(path, tried)
  }

  /**
   * `…/mods/<mod>/<rest>` under OUR game folder, for a path that named someone else's: a relative
   * path, or an absolute one from a differently-installed game (a moved `My Games` folder, a
   * savegame carried over from another machine). Null when the path has no `mods/` in it — a mod
   * loaded from anywhere else can only ever be found where it said it was.
   */
  private fun reanchorInGameMods(gameDir: Path, path: String): Path? {
    val marker = markerIn(path) ?: return null
    val modPart = path.substringAfter(marker)
    val modName = modPart.substringBefore(File.separatorChar).substringBefore('/')
    if (modName.isEmpty() || modPart.length <= modName.length) return null
    return gameDir.resolve("mods").resolve(modName).resolve(modPart.substring(modName.length + 1))
  }

  /**
   * A zipped mod, unpacked only in the engine's eyes: `…/FS25_Map/maps/overview.dds` is really
   * `maps/overview.dds` inside `…/FS25_Map.zip`. Walks the path's ancestors outward and, for the
   * first one that is (or has beside it) a readable zip, returns the remainder as an entry —
   * whatever directory the mod was loaded from, `mods/` or not.
   */
  private fun zipAncestorAsset(resolved: Path, tried: MutableList<String>): ResolvedAsset? {
    var dir = resolved.parent
    while (dir != null) {
      val name = dir.fileName // null on a filesystem root, which is never a mod folder
      val entryName = runCatching { dir.relativize(resolved).toString() }.getOrNull()
      if (name != null && !entryName.isNullOrEmpty()) {
        val entry = entryName.replace('\\', '/')
        // Both spellings the engine can hand us: the zip named like the folder it pretends to be,
        // and — should it ever report the archive itself as a directory — the `.zip` segment as-is.
        for (zip in listOf(dir.resolveSibling("$name.zip"), dir)) {
          if (!zip.isRegularFile()) continue
          tried += "$zip!$entry"
          val bytes = readZipEntry(zip, entry)
          if (bytes != null) {
            log.debug("found {} inside {}", entry, zip)
            return ResolvedAsset(bytes, zip, entry)
          }
          log.debug("{} is a readable zip but holds no entry {}", zip, entry)
        }
      }
      dir = dir.parent
    }
    return null
  }

  /** Bytes of [entryName] in [zipPath], or null when the zip can't be read or has no such entry. */
  private fun readZipEntry(zipPath: Path, entryName: String): ByteArray? = runCatching {
    ZipFile(zipPath.toFile()).use { zip ->
      val entry =
        zip
          .entries()
          .asSequence()
          .firstOrNull { it.name.replace('\\', '/') == entryName }
      entry?.let { zip.getInputStream(it).use { stream -> stream.readBytes() } }
    }
  }.onFailure { log.debug("{} could not be read as a zip: {}", zipPath, it.toString()) }.getOrNull()

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
