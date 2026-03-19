import java.io.File

// Convention plugin applied to ALL subprojects
plugins {
    java
    id("com.diffplug.spotless")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// ── Integration-test source set ──────────────────────────────────────────────
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (Testcontainers-based)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath

    useJUnitPlatform()

    // Give containers time to start
    systemProperty("junit.jupiter.execution.timeout.default", "120s")

    // Report separately from unit tests
    reports {
        html.outputLocation.set(layout.buildDirectory.dir("reports/integrationTests/html"))
        junitXml.outputLocation.set(layout.buildDirectory.dir("reports/integrationTests/xml"))
    }

    configureLocalDockerDesktopSupport()
}

// ── Unit tests ───────────────────────────────────────────────────────────────
tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xmx512m")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// ── Spotless (auto-format) ───────────────────────────────────────────────────
spotless {
    java {
        googleJavaFormat("1.22.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// ── Compiler options ─────────────────────────────────────────────────────────
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Xlint:-processing",   // suppress annotation processing warnings
        "--enable-preview"       // enable preview features for Java 21 (virtual threads)
    ))
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}

fun Test.configureLocalDockerDesktopSupport() {
    val homeDir = File(System.getProperty("user.home"))
    val dockerApiVersion = "1.44"
    val dockerDesktopSocket = listOf(
        File(homeDir, "Library/Containers/com.docker.docker/Data/docker.raw.sock"),
        File(homeDir, ".docker/run/docker.sock")
    ).firstOrNull(File::exists)

    val isolatedHome = layout.buildDirectory.dir("tmp/testcontainers-home")
    doFirst {
        isolatedHome.get().asFile.mkdirs()
    }

    // Ignore stale ~/.testcontainers.properties from the user's real home dir.
    environment("HOME", isolatedHome.map { it.asFile.absolutePath })
    systemProperty("user.home", isolatedHome.map { it.asFile.absolutePath }.get())

    if (dockerDesktopSocket != null) {
        val dockerHost = "unix://${dockerDesktopSocket.absolutePath}"
        environment("DOCKER_HOST", dockerHost)
        environment("DOCKER_API_VERSION", dockerApiVersion)
        environment("api.version", dockerApiVersion)
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        environment("TESTCONTAINERS_HOST_OVERRIDE", "localhost")
        systemProperty("api.version", dockerApiVersion)
        systemProperty(
            "docker.client.strategy",
            "org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy"
        )
        systemProperty("docker.host", dockerHost)
    }
}
