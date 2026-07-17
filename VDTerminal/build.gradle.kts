import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.compose) apply false
  alias(libs.plugins.spotless) apply false
}

val ktlintVersion = libs.versions.ktlint.get()
val composeRulesRuleSet = "io.nlopez.compose.rules:ktlint:${libs.versions.composeRules.get()}"

allprojects {
  group = "net.vertexdezign"
  version = "0.1.0"

  apply(plugin = "com.diffplug.spotless")

  // Only the `app` module holds Compose UI, where the compose ruleset's
  // `compose:function-naming` replaces the standard `function-naming` rule.
  val isComposeModule = path == ":app"

  configure<SpotlessExtension> {
    kotlin {
      // Specify the source explicitly, as not every project applies the kotlin plugin.
      target("src/**/*.kt")
      targetExclude("**/build/**")
      val ktlintConfig =
        ktlint(ktlintVersion)
          .setEditorConfigPath(rootProject.file(".editorconfig"))
          .customRuleSets(listOf(composeRulesRuleSet))
      if (isComposeModule) {
        ktlintConfig.editorConfigOverride(
          mapOf(
            "ktlint_standard_function-naming" to "disabled",
            // App-wide ambient state container, provided once at the root (see state/VdtStore.kt).
            "compose_allowed_composition_locals" to "LocalVdtStore",
          ),
        )
      }
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktlint(ktlintVersion)
    }
  }
}
