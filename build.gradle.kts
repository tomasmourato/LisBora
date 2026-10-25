// Top-level build file. Plugin versions live in gradle/libs.versions.toml.
// kotlin-jvm puts the Kotlin Gradle plugin on the classpath, which AGP's built-in Kotlin also uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
