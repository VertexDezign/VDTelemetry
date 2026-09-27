package net.vertexdezign.vdt.server

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssetResolverTest {
  /** Builds a fake Proton prefix + Steam library and returns (gameDir, assetBytes). */
  private fun fakePrefix(): Triple<Path, Path, ByteArray> {
    val root = Files.createTempDirectory("vdt-prefix")

    // Steam library the S: drive points at.
    val steamapps = root.resolve("lib/steamapps")
    val asset = steamapps.resolve("common/Farming Simulator 25/data/maps/mapUS/overview.dds")
    asset.parent.createDirectories()
    val bytes = byteArrayOf(1, 2, 3, 4, 5)
    asset.writeBytes(bytes)

    // Proton prefix with dosdevices/s: -> the Steam library.
    val prefix = root.resolve("compatdata/2300320/pfx")
    val dosdevices = prefix.resolve("dosdevices")
    dosdevices.createDirectories()
    Files.createSymbolicLink(dosdevices.resolve("s:"), steamapps)

    val gameDir = prefix.resolve("drive_c/users/steamuser/Documents/My Games/FarmingSimulator2025")
    gameDir.createDirectories()
    return Triple(gameDir, root, bytes)
  }

  /** Writes a one-entry zip at [zip] and returns the entry's bytes. */
  private fun zipWith(zip: Path, entryName: String, bytes: ByteArray): ByteArray {
    zip.parent.createDirectories()
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use {
      it.putNextEntry(ZipEntry(entryName))
      it.write(bytes)
      it.closeEntry()
    }
    zip.writeBytes(buffer.toByteArray())
    return bytes
  }

  @Test
  fun resolvesProtonDriveLetterPath() {
    val (gameDir, _, bytes) = fakePrefix()
    val asset =
      AssetResolver.resolve(
        gameDir,
        "S:/common/Farming Simulator 25/data/maps/mapUS/overview.dds",
      )
    assertNotNull(asset, "drive-letter path should resolve via dosdevices")
    assertContentEquals(bytes, asset.bytes)
  }

  @Test
  fun resolvesBackslashDriveLetterPath() {
    val (gameDir, _, bytes) = fakePrefix()
    val asset =
      AssetResolver.resolve(
        gameDir,
        "S:\\common\\Farming Simulator 25\\data\\maps\\mapUS\\overview.dds",
      )
    assertNotNull(asset)
    assertContentEquals(bytes, asset.bytes)
  }

  @Test
  fun missingDriveFileReturnsNull() {
    val (gameDir, _, _) = fakePrefix()
    assertNull(AssetResolver.resolve(gameDir, "S:/common/does/not/exist.dds"))
  }

  // The engine reports a zipped mod's assets as if the zip were a folder. That happens wherever the
  // mod was loaded from, so the zip is looked for beside the pretend folder, not under gameDir/mods.
  @Test
  fun resolvesZippedModOutsideModsFolder() {
    val root = Files.createTempDirectory("vdt-custom-mods")
    val bytes = byteArrayOf(9, 8, 7)
    zipWith(root.resolve("Projekt_MM/FS25_D_Am_Mittellandkanal.zip"), "maps/overview.dds", bytes)

    val asset =
      AssetResolver.resolve(
        root.resolve("game"),
        "$root/Projekt_MM/FS25_D_Am_Mittellandkanal/maps/overview.dds",
      )
    assertNotNull(asset, "a zipped mod outside mods/ should resolve through its sibling zip")
    assertContentEquals(bytes, asset.bytes)
  }

  // The classic layout still resolves — now through the same sibling-zip walk.
  @Test
  fun resolvesZippedModInModsFolder() {
    val gameDir = Files.createTempDirectory("vdt-game")
    val bytes = byteArrayOf(4, 5, 6)
    zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", bytes)

    val asset = AssetResolver.resolve(gameDir, "$gameDir/mods/FS25_Map/maps/overview.dds")
    assertNotNull(asset)
    assertContentEquals(bytes, asset.bytes)
  }

  // An unpacked mod folder beside a stale zip: the real file is the answer.
  @Test
  fun realFileWinsOverSiblingZip() {
    val gameDir = Files.createTempDirectory("vdt-game")
    zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", byteArrayOf(0))
    val unpacked = gameDir.resolve("mods/FS25_Map/maps/overview.dds")
    unpacked.parent.createDirectories()
    val bytes = byteArrayOf(1, 1, 2, 3)
    unpacked.writeBytes(bytes)

    val asset = AssetResolver.resolve(gameDir, unpacked.toString())
    assertNotNull(asset)
    assertContentEquals(bytes, asset.bytes)
  }

  @Test
  fun missingEntryInSiblingZipReturnsNull() {
    val gameDir = Files.createTempDirectory("vdt-game")
    zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", byteArrayOf(0))
    assertNull(AssetResolver.resolve(gameDir, "$gameDir/mods/FS25_Map/maps/pda.dds"))
  }

  // The only thing the reported path itself can't answer: it names a mods folder that isn't ours —
  // another machine's install, or a relative path — so it is re-anchored onto <gameDir>/mods.
  @Test
  fun reanchorsForeignModsPathOntoGameDir() {
    val gameDir = Files.createTempDirectory("vdt-game")
    val bytes = byteArrayOf(7, 7, 7)
    zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", bytes)

    val asset =
      AssetResolver.resolve(
        gameDir,
        "C:/Users/someone-else/Documents/My Games/FarmingSimulator2025/mods/FS25_Map/maps/overview.dds",
      )
    assertNotNull(asset, "a mods/ path from another install should re-anchor onto gameDir")
    assertContentEquals(bytes, asset.bytes)
  }

  @Test
  fun doesNotReanchorAPathOutsideMods() {
    val gameDir = Files.createTempDirectory("vdt-game")
    zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", byteArrayOf(0))
    assertNull(AssetResolver.resolve(gameDir, "D:/Projekt_MM/FS25_Map/maps/overview.dds"))
  }

  // A released map names overview.png and ships only the .dds, inside its zip.
  @Test
  fun aDeclaredPngThatShipsAsDdsIsFoundAsTheDds() {
    val gameDir = Files.createTempDirectory("vdt-game")
    val bytes = zipWith(gameDir.resolve("mods/FS25_Map.zip"), "maps/overview.dds", byteArrayOf(4, 2))
    val lookup = AssetResolver.lookupTexture(gameDir, "$gameDir/mods/FS25_Map/maps/overview.png")
    val asset = assertNotNull(lookup.asset)
    assertContentEquals(bytes, asset.bytes)
    assertEquals("maps/overview.dds", asset.entry)
  }

  // A map still in development may have only the PNG it declares -- or both, where the PNG is the
  // one being worked on. Either way the declared file is the one served.
  @Test
  fun aDeclaredPngThatIsThereWinsOverTheDds() {
    val gameDir = Files.createTempDirectory("vdt-game")
    val maps = gameDir.resolve("mods/FS25_Map/maps")
    maps.createDirectories()
    maps.resolve("overview.png").writeBytes(byteArrayOf(1))
    maps.resolve("overview.dds").writeBytes(byteArrayOf(2))
    val asset = assertNotNull(AssetResolver.lookupTexture(gameDir, "$maps/overview.png").asset)
    assertContentEquals(byteArrayOf(1), asset.bytes)
  }
}
