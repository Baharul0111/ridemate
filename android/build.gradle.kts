plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.room) apply false
    // Third-party licence report for what ships in the APK (tools/license_report.py, license-report.yml).
    id("com.github.jk1.dependency-license-report") version "3.1.4" apply false
}

// Licence report: applied to :app only, over the release runtime classpath (what the APK actually contains).
// Run: ./gradlew --no-parallel :app:generateLicenseReport -> app/build/reports/dependency-license/
project(":app") {
    pluginManager.withPlugin("com.android.application") {
        pluginManager.apply("com.github.jk1.dependency-license-report")
        extensions.configure<com.github.jk1.license.LicenseReportExtension> {
            configurations = arrayOf("releaseRuntimeClasspath")
            excludeBoms = true
            filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(
                com.github.jk1.license.filter.LicenseBundleNormalizer(),
            )
            renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
                com.github.jk1.license.render.JsonReportRenderer("index.json", false),
                com.github.jk1.license.render.InventoryHtmlReportRenderer("index.html", "RideMate Android (release)"),
            )
        }
    }
}
