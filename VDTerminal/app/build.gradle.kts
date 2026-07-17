import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      commonWebpackConfig {
        outputFileName = "app.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(projects.shared)
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.material3)
      implementation(compose.materialIconsExtended)
      implementation(compose.ui)
      implementation(compose.components.resources)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.js)
      implementation(libs.ktor.client.websockets)
      implementation(libs.multiplatform.settings)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}

compose.resources {
  packageOfResClass = "net.vertexdezign.vdt.app.resources"
}

// The wasmJs tests run in headless Chrome via karma, which needs CHROME_BIN. CI (ubuntu-latest) has
// google-chrome on PATH so the launcher finds it unaided; local dev machines usually only have
// Chromium, so point karma at it there. An explicit CHROME_BIN always wins, and we never override on
// CI or when the binary is absent (e.g. macOS), so this only smooths the common Linux-dev case.
tasks.withType<KotlinJsTest>().configureEach {
  val chromium = file("/usr/bin/chromium")
  if (System.getenv("CI") == null && System.getenv("CHROME_BIN") == null && chromium.exists()) {
    environment("CHROME_BIN", chromium.absolutePath)
  }
}
