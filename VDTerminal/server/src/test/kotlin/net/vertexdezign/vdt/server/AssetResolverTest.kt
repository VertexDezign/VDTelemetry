package net.vertexdezign.vdt.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test
    fun resolvesProtonDriveLetterPath() {
        val (gameDir, _, bytes) = fakePrefix()
        val asset = AssetResolver.resolve(
            gameDir,
            "S:/common/Farming Simulator 25/data/maps/mapUS/overview.dds",
        )
        assertNotNull(asset, "drive-letter path should resolve via dosdevices")
        assertContentEquals(bytes, asset.bytes)
    }

    @Test
    fun resolvesBackslashDriveLetterPath() {
        val (gameDir, _, bytes) = fakePrefix()
        val asset = AssetResolver.resolve(
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
}
