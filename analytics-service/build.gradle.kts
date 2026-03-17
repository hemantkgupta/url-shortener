plugins {
    id("url-shortener.spring-conventions")
}

configurations {
    // Flink is "provided" at runtime in cluster mode — exclude from fat jar
    val flinkProvided by creating
    compileOnly { extendsFrom(flinkProvided) }
    testImplementation { extendsFrom(flinkProvided) }
}

dependencies {
    implementation(project(":core"))

    // Spring web — analytics REST API
    implementation(libs.bundles.spring.web.base)

    // ── Flink (provided — cluster runtime supplies these) ─────────────────────
    val flinkProvided by configurations
    flinkProvided(libs.flink.streaming.java)
    flinkProvided(libs.flink.clients)
    flinkProvided(libs.flink.connector.base)
    flinkProvided(libs.flink.connector.kafka)

    // Kafka (Spring Kafka for non-Flink paths — real-time Redis counters)
    implementation(libs.spring.kafka)

    // ScyllaDB — query url_mapping_by_user for analytics API
    implementation(libs.bundles.cassandra)

    // Redis — real-time click counters (INCR click_count:{key})
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.lettuce.core)

    // ClickHouse — OLAP store for historical analytics
    implementation(libs.clickhouse.jdbc)

    // Observability
    implementation(libs.bundles.observability)

    // Jackson
    implementation(platform(libs.jackson.bom))
    implementation(libs.bundles.jackson.full)

    // ── Unit tests ────────────────────────────────────────────────────────────
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.testing.unit)
    testImplementation(libs.spring.kafka.test)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ── Integration tests ─────────────────────────────────────────────────────
    val integrationTestImplementation by configurations
    integrationTestImplementation(platform(libs.testcontainers.bom))
    integrationTestImplementation(libs.bundles.testing.integration)
    integrationTestImplementation(libs.testcontainers.kafka)
    integrationTestImplementation(libs.testcontainers.cassandra)
    integrationTestImplementation(libs.testcontainers.clickhouse)
    integrationTestImplementation(libs.testcontainers.redis)
    integrationTestImplementation(libs.spring.kafka.test)
    integrationTestImplementation(libs.spring.boot.starter.test)
    integrationTestImplementation(libs.awaitility)
}

// Fat jar with Flink bundled for local/embedded Flink mode
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Include Flink in fat jar for local dev (embedded mini-cluster)
    classpath(configurations["flinkProvided"])
}

jib {
    to {
        image = "url-shortener/analytics-service:${project.version}"
    }
    container {
        mainClass = "com.urlshortener.analytics.AnalyticsServiceApplication"
        ports = listOf("8083", "9093")
    }
    extraDirectories {
        // Include Flink libs as a separate layer for faster Docker rebuilds
        paths {
            path {
                setFrom(configurations["flinkProvided"])
                into = "/flink/lib"
            }
        }
    }
}

springBoot {
    mainClass.set("com.urlshortener.analytics.AnalyticsServiceApplication")
}
