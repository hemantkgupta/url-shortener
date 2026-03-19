rootProject.name = "url-shortener"

// Enable version catalog
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    "core",
    "key-generation-service",
    "write-service",
    "redirect-service",
    "analytics-service",
    "infrastructure:performance-tests"
)

// Frontend is a separate npm project — wired in via a wrapper task only
// include("frontend")  // uncomment if you want Gradle to drive npm builds

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // gradle/libs.versions.toml is auto-discovered by Gradle 8 as the "libs" catalog —
    // no explicit versionCatalogs block needed; adding one would load it twice.
}
