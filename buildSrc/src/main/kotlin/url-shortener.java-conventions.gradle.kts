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
