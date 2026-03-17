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
    versionCatalogs {
        create("libs") {
            from(files("gradle/libs.versions.toml"))
        }
    }
}
