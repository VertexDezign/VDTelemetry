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

// The .editorconfig lives at the *repo* root, a directory above this build, because that is where
// the IDE's own ktlint reads it from: the plugin takes the .editorconfig of the folder IntelliJ was
// opened on and ignores one further down.
//
// Resolved through the parent rather than written as "../.editorconfig", and that is not cosmetic --
// handed the `..` form, ktlint applies none of the file's properties and formats to its own defaults
// instead. Silently: nothing warns, the build simply starts asking for different code. Whatever the
// path is spelled as, it has to come out of here with the `..` already resolved.
val repoEditorConfig = rootDir.parentFile.resolve(".editorconfig")
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
          .setEditorConfigPath(repoEditorConfig)
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
      // Named here too, where it never had to be before: with the file inside this build ktlint found
      // it for itself, and from the repo root it does not. Left unnamed, this step formats the build
      // scripts to ktlint's defaults -- which is how the move was noticed, on app/build.gradle.kts.
      ktlint(ktlintVersion).setEditorConfigPath(repoEditorConfig)
    }
  }
}
