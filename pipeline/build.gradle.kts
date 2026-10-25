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
    // Importing Portugal's OSM extract with GraphHopper needs a few GB.
    maxHeapSize = "8g"
    // Portuguese output: force UTF-8 regardless of the Windows console code page.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

// Checks the reference routes against the graph built by `run --args="build"`.
tasks.register<JavaExec>("goldenRoutes") {
    group = "verification"
    description = "Runs the golden routes against pipeline/work/datapack/graph.lbg."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    workingDir = rootDir
    maxHeapSize = "2g"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    args("golden-routes")
}

dependencies {
    implementation(project(":core:routing"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.graphhopper.core)
    implementation(libs.twelvemonkeys.imageio.webp)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
