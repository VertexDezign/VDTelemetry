import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Pinned so the artifact matches the documented "JDK 21+", whatever JDK built it — CI runs 25,
  // and without this the class files it emits load on nothing older. `-Xjdk-release` is the half
  // that bites: the bytecode version alone still lets a newer JDK's API link into a class file
  // stamped 21, and that failure lands on the user's machine rather than in CI.
  jvm {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
      freeCompilerArgs.add("-Xjdk-release=21")
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
