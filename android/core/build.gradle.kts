// Pure-Kotlin RideMate logic: no Android dependencies, so every rule here is unit-tested on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.re2j)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The accuracy and replay suites read fixtures from the repo's eval/ folder.
    val repoRoot = rootProject.projectDir.parentFile
    systemProperty("ridemate.repoRoot", repoRoot.absolutePath)
    // -PrequireFixtures=true turns a missing eval fixture into a failure instead of a skip (CI with fixtures).
    systemProperty("ridemate.requireFixtures", (findProperty("requireFixtures") as String?) ?: "false")
    // AccuracyTest writes <suite>.json here for eval/harness/summarize.py (outcomes only, never fare values).
    val accuracyDir = layout.buildDirectory.dir("accuracy").get().asFile
    systemProperty("ridemate.accuracyDir", accuracyDir.absolutePath)
    outputs.dir(accuracyDir).withPropertyName("accuracyResults")
    // Declared as inputs so changed fixtures or bundled configs re-run the suites instead of hitting the cache.
    inputs.files(fileTree(repoRoot.resolve("eval")) { include("**/*.json"); exclude("out/**", "private/**", "**/.*/**") })
        .withPropertyName("evalFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree(repoRoot.resolve("config/remote_config")) { include("*.json") },
        fileTree(repoRoot.resolve("android/app/src/main/assets/config")) { include("*.json") },
    ).withPropertyName("bundledConfig").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
