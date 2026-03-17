plugins {
    id("url-shortener.spring-conventions")
}

dependencies {
    implementation(project(":core"))

    // Spring web
    implementation(libs.bundles.spring.web.base)

    // etcd — Raft-based counter block allocation
    implementation(libs.jetcd.core)

    // Redis — Bloom Filter (RedisBloom via Redisson)
    implementation(libs.redisson)
    implementation(libs.spring.boot.starter.data.redis)

    // Observability
    implementation(libs.bundles.observability)

    // Jackson
    implementation(platform(libs.jackson.bom))
    implementation(libs.bundles.jackson.full)

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ── Integration tests ─────────────────────────────────────────────────────
    val integrationTestImplementation by configurations
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.bundles.testing.integration)
    integrationTestImplementation(libs.testcontainers.redis)
    integrationTestImplementation(libs.jetcd.test)   // embedded etcd for tests
    integrationTestImplementation(libs.spring.kafka.test)
}

jib {
    to {
        image = "url-shortener/key-generation-service:${project.version}"
    }
    container {
        mainClass = "com.urlshortener.kgs.KeyGenerationServiceApplication"
        ports = listOf("8081", "9091")
    }
}

springBoot {
    mainClass.set("com.urlshortener.kgs.KeyGenerationServiceApplication")
}
