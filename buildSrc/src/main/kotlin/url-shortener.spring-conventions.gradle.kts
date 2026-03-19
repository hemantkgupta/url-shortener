import org.gradle.api.artifacts.VersionCatalogsExtension

// Convention plugin applied to Spring Boot service subprojects
plugins {
    id("url-shortener.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val testcontainersVersion = libs.findVersion("testcontainers").get().requiredVersion

// Let root BOM manage Spring Boot versions for all dependencies
dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.testcontainers:testcontainers-bom:$testcontainersVersion")
    }
}

// Common Spring Boot dependencies present in every service
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-junit-jupiter")
}

// Jib: build OCI images without Docker daemon (Mac-friendly)
// Override image name per-module via jib { to { image = "..." } }
jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
    container {
        jvmFlags = listOf(
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-Xms256m",
            "-Xmx1g",
            "--enable-preview",
            // OTel Java agent injected at runtime via JAVA_TOOL_OPTIONS env var in Docker/K8s
        )
        ports = listOf("8080", "9090") // app + prometheus scrape
        environment = mapOf(
            "SPRING_PROFILES_ACTIVE" to "prod"
        )
    }
}

// Spring Boot default bootJar naming
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}
