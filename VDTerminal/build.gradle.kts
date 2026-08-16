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
  // The release workflow passes the git tag through as -PvdtVersion=0.1.0-alpha.1, so the published
  // archives carry the tag's name. The fallback is a placeholder for local builds and nothing else:
  // the tag is the only place a version is authored, and a real-looking number here would only be a
  // second one to forget to bump. It keeps the release's shape -- MAJOR.MINOR.PATCH plus a
  // prerelease -- because jpackage takes the numeric head and accepts three components at most.
  version = (findProperty("vdtVersion") as String?)?.takeIf { it.isNotBlank() } ?: "0.0.0-dev"

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
            // App-wide ambient state container, provided once at the root (see state/VdtStore.kt);
            // screen navigation, which user-placed widgets have no call chain to reach (Navigator.kt);
            // and which placed tile is rendering, which only the few widgets holding view state of
            // their own ever read, so it isn't worth a parameter on every widget (widgets/WidgetSettings.kt).
            "compose_allowed_composition_locals" to "LocalVdtStore,LocalNavigator,LocalWidgetInstance",
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
