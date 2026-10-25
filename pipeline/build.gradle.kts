import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Data pipeline CLI (runs on the PC/CI, never on the phone).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("pt.lisbora.pipeline.MainKt")
}

// Resolve CLI paths (--out, tools) against the repository root, not the module directory.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    // Portuguese output: force UTF-8 regardless of the Windows console code page.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    implementation(project(":core:routing"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
