// Root build — only orchestration tasks, no source code here
plugins {
    alias(libs.plugins.gradle.versions)   // ./gradlew dependencyUpdates
    // NOTE: spotless and jib are declared as buildSrc classpath dependencies and applied
    // via convention plugins (url-shortener.java-conventions, url-shortener.spring-conventions).
    // Do NOT re-declare them here with a version — Gradle 8 rejects duplicate plugin
    // declarations when the plugin is already on the classpath from buildSrc.
}

group = "com.urlshortener"
version = "1.0.0-SNAPSHOT"

// Apply the java conventions plugin to every subproject that has Java source
subprojects {
    group = rootProject.group
    version = rootProject.version
}

// ── Convenience tasks ────────────────────────────────────────────────────────

// Build all services (skip frontend)
tasks.register("buildAll") {
    group = "url-shortener"
    description = "Builds all Java modules"
    dependsOn(
        ":core:build",
        ":key-generation-service:build",
        ":write-service:build",
        ":redirect-service:build",
        ":analytics-service:build"
    )
}

// Run all unit tests
tasks.register("testAll") {
    group = "url-shortener"
    description = "Runs unit tests for all Java modules"
    dependsOn(
        ":core:test",
        ":key-generation-service:test",
        ":write-service:test",
        ":redirect-service:test",
        ":analytics-service:test"
    )
}

// Run all integration tests
tasks.register("integrationTestAll") {
    group = "url-shortener"
    description = "Runs integration tests (Testcontainers) for all Java modules"
    dependsOn(
        ":core:integrationTest",
        ":key-generation-service:integrationTest",
        ":write-service:integrationTest",
        ":redirect-service:integrationTest",
        ":analytics-service:integrationTest"
    )
}

// Build all Docker images (uses Jib — no Docker daemon needed)
tasks.register("buildImages") {
    group = "url-shortener"
    description = "Builds OCI images for all services via Jib"
    dependsOn(
        ":key-generation-service:jibDockerBuild",
        ":write-service:jibDockerBuild",
        ":redirect-service:jibDockerBuild",
        ":analytics-service:jibDockerBuild"
    )
}

// Run Gatling performance tests
tasks.register("perfTest") {
    group = "url-shortener"
    description = "Runs Gatling performance simulations"
    dependsOn(":infrastructure:performance-tests:gatlingRun")
}
