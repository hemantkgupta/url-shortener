plugins {
    id("url-shortener.spring-conventions")
}

dependencies {
    implementation(project(":core"))

    // Spring web
    implementation(libs.bundles.spring.web.base)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)

    // ScyllaDB / Cassandra driver
    implementation(libs.bundles.cassandra)
    annotationProcessor(libs.cassandra.driver.mapper.processor)

    // Redis (write-through cache warm + Bloom Filter)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.redisson)
    implementation(libs.lettuce.core)

    // Kafka (url.created events)
    implementation(libs.spring.kafka)

    // Observability
    implementation(libs.bundles.observability)

    // Jackson
    implementation(platform(libs.jackson.bom))
    implementation(libs.bundles.jackson.full)

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.wiremock)                       // stub KGS and Safe Browsing
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.spring.security.test)
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
    integrationTestImplementation(libs.spring.security.test)
}

jib {
    to {
        image = "url-shortener/write-service:${project.version}"
    }
    container {
        mainClass = "com.urlshortener.write.WriteServiceApplication"
        ports = listOf("8082", "9092")
    }
}

springBoot {
    mainClass.set("com.urlshortener.write.WriteServiceApplication")
}
