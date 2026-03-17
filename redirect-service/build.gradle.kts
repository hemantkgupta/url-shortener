plugins {
    id("url-shortener.spring-conventions")
}

dependencies {
    implementation(project(":core"))

    // Spring web (virtual threads configured in application.yml)
    implementation(libs.bundles.spring.web.base)

    // ScyllaDB — DB fallback after cache miss
    implementation(libs.bundles.cassandra)

    // Redis — L2 cache + Bloom Filter gate
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.redisson)               // RedisBloom BF.EXISTS
    implementation(libs.lettuce.core)           // async lettuce for non-blocking reads

    // Kafka — fire-and-forget click event publishing
    implementation(libs.spring.kafka)

    // Observability
    implementation(libs.bundles.observability)

    // Jackson
    implementation(platform(libs.jackson.bom))
    implementation(libs.bundles.jackson.full)

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.wiremock)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ── Integration tests ─────────────────────────────────────────────────────
    val integrationTestImplementation by configurations
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.bundles.testing.integration)
    integrationTestImplementation(libs.testcontainers.cassandra)
    integrationTestImplementation(libs.testcontainers.redis)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.spring.kafka.test)
    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.awaitility)
}

jib {
    to {
        image = "url-shortener/redirect-service:${project.version}"
    }
    container {
        mainClass = "com.urlshortener.redirect.RedirectServiceApplication"
        // Virtual threads handle massive concurrency at low memory
        jvmFlags = listOf(
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-Xms256m",
            "-Xmx2g",       // redirect service carries most load
            "--enable-preview"
        )
        ports = listOf("8080", "9090")
    }
}

springBoot {
    mainClass.set("com.urlshortener.redirect.RedirectServiceApplication")
}
