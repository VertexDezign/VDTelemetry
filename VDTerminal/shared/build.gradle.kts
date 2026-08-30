import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Pinned so the artifact matches the documented "JDK 25+", whatever JDK built it — a developer
  // on 26 must not produce class files the release runtime can't load. `-Xjdk-release` is the half
  // that bites: the bytecode version alone still lets a newer JDK's API link into a class file
  // stamped 25, and that failure lands on the user's machine rather than in CI.
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_25
      freeCompilerArgs.add("-Xjdk-release=25")
    }
  }
  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.kotlinx.coroutines.core)
    }
    commonTest.dependencies {
      implementation(kotlin("test"))
    }
  }
}

// The JVM tests parse the committed captures in `examples/json/` — the mod↔Kotlin contract, checked
// against real game output rather than hand-written JSON. Those files live outside this module (the
// tests find them by walking up from the working directory), so Gradle cannot infer them from the
// task graph: re-capturing a fixture left `jvmTest` UP-TO-DATE and the suite went on reporting a
// pass against the previous bytes, which is the one failure mode a fixture-driven suite must not
// have. Declaring the directory makes a new capture re-run the tests that read it.
tasks.named<Test>("jvmTest") {
  inputs
    .dir(layout.settingsDirectory.dir("../examples/json"))
    .withPropertyName("exampleCaptures")
    // The tests locate a fixture by path from the repo root, so where the checkout sits does not
    // matter but what a file is called does.
    .withPathSensitivity(PathSensitivity.RELATIVE)
}
