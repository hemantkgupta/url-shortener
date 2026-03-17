// core — plain Java library, no Spring Boot, no fat jar
plugins {
    id("url-shortener.java-conventions")
    `java-library`
}

dependencies {
    // Domain / DTO serialization
    api(platform(libs.jackson.bom))
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.slf4j.api)

    // Validation annotations only (no Spring wiring)
    api("jakarta.validation:jakarta.validation-api:3.1.0")
    implementation(libs.commons.lang3)
    implementation(libs.guava)

    // Unit tests
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.unit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// core is a pure library — no bootJar, no application plugin
tasks.jar {
    enabled = true
}
