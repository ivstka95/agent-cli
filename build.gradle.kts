// Root build: declare the Kotlin plugins once on the build classpath (apply false) so the
// `app` and `mcp` subprojects can apply them without each loading the plugin separately.
// See: https://docs.gradle.org/current/userguide/plugins.html#sec:subprojects_plugins_dsl
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
