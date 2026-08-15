import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

dependencies {
  implementation(projects.shared)

  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logback.classic)

  testImplementation(kotlin("test"))
  testImplementation(libs.ktor.server.test.host)
}

// Pinned so the artifact matches the documented "JDK 21+", whatever JDK built it. `-Xjdk-release`
// is the half that bites: without it a JDK 25 build happily links a JDK 25 API into class files
// stamped 21, and the failure lands on the user's machine rather than in CI.
kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
    freeCompilerArgs.add("-Xjdk-release=21")
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

application {
  mainClass.set("net.vertexdezign.vdt.server.ServerKt")
}

// Bundle the production wasm app into the server's resources under `static/`, so a single
// server artifact serves both the dashboard and the API/WebSocket.
tasks.named<ProcessResources>("processResources") {
  dependsOn(":app:wasmJsBrowserDistribution")
  from(rootProject.layout.projectDirectory.dir("app/build/dist/wasmJs/productionExecutable")) {
    into("static")
  }
}

// --- Release packaging -------------------------------------------------------------------------
//
// `installDist` already produces a runnable tree, but it needs a JDK on the machine — which is the
// one thing a Farming Simulator player has no reason to have. jpackage wraps that same tree with a
// JRE, so the download is "unzip, run", and the JDK requirement applies only to the portable
// distZip. jpackage cannot cross-compile: each OS image is built on its own runner.

private val appName = "VDTerminal"

// jpackage takes a numeric version only, so a prerelease tag's suffix is dropped for the image's
// own metadata. The archive around it keeps the full name.
private val appVersion = version.toString().substringBefore('-')

private val isWindows =
  providers
    .systemProperty("os.name")
    .get()
    .lowercase()
    .startsWith("win")

private val jpackageBin =
  providers.systemProperty("java.home").map { "$it/bin/jpackage" + if (isWindows) ".exe" else "" }

private val jpackageDir = layout.buildDirectory.dir("jpackage")
private val installDir = layout.buildDirectory.dir("install/server")

// jpackage refuses to write into a directory that already holds the image, and the task's own
// output is exactly that directory — so it is cleared first rather than left to fail on the second
// run.
private val clearJpackageImage =
  tasks.register<Delete>("clearJpackageImage") {
    delete(jpackageDir)
  }

private val jpackageImage =
  tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Self-contained app image with a bundled JRE, for the OS this build runs on."
    dependsOn(tasks.installDist, clearJpackageImage)

    inputs.dir(installDir)
    outputs.dir(jpackageDir)

    commandLine(
      buildList {
        add(jpackageBin.get())
        addAll(listOf("--type", "app-image"))
        addAll(listOf("--name", appName))
        addAll(listOf("--app-version", appVersion))
        addAll(listOf("--vendor", "VertexDezign"))
        addAll(listOf("--description", "VDTelemetry dashboard for Farming Simulator 25"))
        addAll(
          listOf(
            "--input",
            installDir
              .get()
              .dir("lib")
              .asFile.absolutePath,
          ),
        )
        addAll(
          listOf(
            "--main-jar",
            tasks.jar
              .get()
              .archiveFileName
              .get(),
          ),
        )
        addAll(listOf("--main-class", "net.vertexdezign.vdt.server.ServerKt"))
        addAll(listOf("--dest", jpackageDir.get().asFile.absolutePath))
        // A console window is the only place the startup log can be read, and it carries the two
        // things a first run needs: the LAN address to open on the tablet, and the warning when the
        // game directory wasn't found. A windowed launcher would show neither.
        if (isWindows) add("--win-console")
      },
    )
  }

/**
 * The three files jpackage marks executable: the launcher, and two helpers the bundled runtime
 * shells out to.
 *
 * Gradle's archive tasks normalise permissions to 644 on the way in, which turns `bin/VDTerminal`
 * into a file the user cannot run — so the bit is handed back explicitly. Nothing else in the image
 * carries it, the `.so` files included.
 */
private val executablesInImage =
  listOf("**/bin/*", "**/lib/runtime/lib/jexec", "**/lib/runtime/lib/jspawnhelper")

/**
 * What ships beside the program in every download: the licences and the setup instructions.
 *
 * The repo's own terms, and the attribution the bundled DSEG fonts' OFL requires travel with them —
 * an archive is the only place a player ever sees either. The fonts are inside the jar, so this
 * applies to the portable distribution exactly as much as to the two bundled-JRE ones.
 */
private fun CopySpec.releaseNotices() {
  from(rootProject.file("licenses")) { into("licenses") }
  from(rootProject.file("../docs")) { include("setup.*.md") }
  from(rootProject.file("../LICENSE"))
  from(rootProject.file("../NOTICE"))
}

/** The app image, plus what ships beside it. */
private fun CopySpec.releaseContents() {
  from(jpackageDir) {
    filesMatching(executablesInImage) { permissions { unix("755") } }
  }
  releaseNotices()
}

// The portable distribution (`distZip`/`installDist`) gets the same paperwork. Only the tree's root
// is touched, and jpackage takes its `--input` from `install/server/lib`, so nothing here reaches
// the app image twice.
distributions {
  named("main") {
    contents { releaseNotices() }
  }
}

private val jpackageZip =
  tasks.register<Zip>("jpackageZip") {
    dependsOn(jpackageImage)
    releaseContents()
    archiveFileName.set("$appName-$version-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
  }

// tar rather than zip on Linux: zip carries no executable bit at all, so the launcher would arrive
// unrunnable however it was set here.
private val jpackageTar =
  tasks.register<Tar>("jpackageTar") {
    dependsOn(jpackageImage)
    releaseContents()
    compression = Compression.GZIP
    archiveFileName.set("$appName-$version-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
  }

tasks.register("packageRelease") {
  group = "distribution"
  description = "Bundled-JRE release archive for the OS this build runs on (build/release/)."
  dependsOn(if (isWindows) jpackageZip else jpackageTar)
}
