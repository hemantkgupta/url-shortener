plugins {
    id("url-shortener.java-conventions")
    alias(libs.plugins.gatling)
}

// Gatling uses Scala DSL
repositories {
    mavenCentral()
}

dependencies {
    gatling("io.gatling.highcharts:gatling-charts-highcharts:${libs.versions.gatling.get()}")
}

// Target URLs (override via -PbaseUrl=... on CLI or env var)
val baseUrl: String = project.findProperty("baseUrl") as String? ?: "http://localhost:8080"
val writeUrl: String = project.findProperty("writeUrl") as String? ?: "http://localhost:8082"

gatling {
    // Pass target URLs into simulations as system properties
    jvmArgs = listOf(
        "-DbaseUrl=$baseUrl",
        "-DwriteUrl=$writeUrl"
    )
    logLevel = "WARN"
}

// Convenient named tasks per simulation
tasks.register("perfRedirect") {
    group = "url-shortener-perf"
    description = "Redirect hot-path load test (ramp 0 → 5000 RPS)"
    dependsOn(tasks.named("gatlingRun-simulations.RedirectSimulation"))
}

tasks.register("perfWrite") {
    group = "url-shortener-perf"
    description = "Write-path load test (ramp 0 → 500 RPS)"
    dependsOn(tasks.named("gatlingRun-simulations.ShortenUrlSimulation"))
}

tasks.register("perfMixed") {
    group = "url-shortener-perf"
    description = "Mixed 100:1 read/write load test"
    dependsOn(tasks.named("gatlingRun-simulations.MixedLoadSimulation"))
}
