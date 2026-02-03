plugins {
    java
    application
    jacoco                                      // Code coverage
    checkstyle                                  // Code style
    id("com.github.spotbugs") version "6.0.7"  // Bug detection
    id("org.owasp.dependencycheck") version "9.0.9"  // Security vulnerabilities
}

group = "com.ig.tfl"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

val pekkoVersion = "1.4.0"
val pekkoHttpVersion = "1.3.0"
val jacksonVersion = "2.17.0"
val micrometerVersion = "1.12.0"
val openTelemetryVersion = "1.34.0"

dependencies {
    // Metrics (Prometheus)
    implementation("io.micrometer:micrometer-registry-prometheus:$micrometerVersion")

    // Tracing (OpenTelemetry) - manual instrumentation for TfL API calls
    implementation("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    implementation("io.opentelemetry:opentelemetry-sdk:$openTelemetryVersion")
    implementation("io.opentelemetry:opentelemetry-exporter-logging:$openTelemetryVersion")

    // Pekko Core
    implementation("org.apache.pekko:pekko-actor-typed_2.13:$pekkoVersion")
    implementation("org.apache.pekko:pekko-cluster-typed_2.13:$pekkoVersion")
    implementation("org.apache.pekko:pekko-distributed-data_2.13:$pekkoVersion")

    // Pekko HTTP (for REST API)
    implementation("org.apache.pekko:pekko-http_2.13:$pekkoHttpVersion")
    implementation("org.apache.pekko:pekko-http-jackson_2.13:$pekkoHttpVersion")
    implementation("org.apache.pekko:pekko-stream_2.13:$pekkoVersion")

    // Jackson for JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Logging
    implementation("org.apache.pekko:pekko-slf4j_2.13:$pekkoVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Testing
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_2.13:$pekkoVersion")
    testImplementation("org.apache.pekko:pekko-multi-node-testkit_2.13:$pekkoVersion")
    testImplementation("org.apache.pekko:pekko-http-testkit_2.13:$pekkoHttpVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.wiremock:wiremock:3.4.2")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$openTelemetryVersion")

    // Testcontainers for Docker-based integration tests
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("org.testcontainers:toxiproxy:1.19.3")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ig.tfl.TflApplication")
}

// =============================================================================
// Testing Configuration
// =============================================================================

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e", "container")
    }
    finalizedBy(tasks.jacocoTestReport)  // Generate coverage after tests
}

// E2E tests that hit real TfL API - run separately
tasks.register<Test>("e2eTest") {
    description = "Runs E2E smoke tests against real TfL API"
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
    }
}

// Container-based integration tests (requires Docker)
tasks.register<Test>("containerTest") {
    description = "Runs Testcontainers-based integration tests"
    group = "verification"
    useJUnitPlatform {
        includeTags("container")
    }
    testLogging {
        events("passed", "skipped", "failed", "standardOut")
        showStandardStreams = true
    }
}

// Run all test suites
tasks.register("allTests") {
    description = "Runs all test suites (unit, container, e2e)"
    group = "verification"
    dependsOn("test", "containerTest", "e2eTest")
}

// =============================================================================
// JaCoCo - Code Coverage
// =============================================================================

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)   // For CI integration
        html.required.set(true)  // Human-readable report
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                // Minimum 70% line coverage
                minimum = "0.70".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                // Minimum 55% branch coverage (current: ~58%)
                minimum = "0.55".toBigDecimal()
            }
        }
    }
}

// =============================================================================
// Checkstyle - Code Style
// =============================================================================

checkstyle {
    toolVersion = "10.12.5"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false  // Fail build on violations
    maxWarnings = 0
}

tasks.checkstyleMain {
    source = fileTree("src/main/java")
}

tasks.checkstyleTest {
    source = fileTree("src/test/java")
}

// =============================================================================
// SpotBugs - Bug Detection
// =============================================================================

spotbugs {
    ignoreFailures.set(false)
    showStackTraces.set(true)
    showProgress.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.spotbugsMain {
    reports.create("html") {
        required.set(true)
        outputLocation.set(file("${layout.buildDirectory.get()}/reports/spotbugs/main.html"))
    }
    reports.create("xml") {
        required.set(true)
    }
}

tasks.spotbugsTest {
    reports.create("html") {
        required.set(true)
    }
}

// =============================================================================
// OWASP Dependency Check - Security Vulnerabilities
// =============================================================================

dependencyCheck {
    failBuildOnCVSS = 7.0f  // Fail on HIGH or CRITICAL vulnerabilities
    formats = listOf("HTML", "JSON")
    suppressionFile = "config/owasp/suppressions.xml"
    analyzers.apply {
        assemblyEnabled = false  // Disable .NET analyzer
        nodeEnabled = false      // Disable Node.js analyzer
    }
}

// =============================================================================
// Quality Gate - Run all checks
// =============================================================================

tasks.register("qualityCheck") {
    description = "Runs all quality checks (tests, coverage, style, bugs, security)"
    group = "verification"
    dependsOn(
        "test",
        "jacocoTestCoverageVerification",
        "checkstyleMain",
        "checkstyleTest",
        "spotbugsMain",
        "spotbugsTest"
    )
}

// Full verification including security scan (slower)
tasks.register("fullCheck") {
    description = "Runs all checks including OWASP dependency scan"
    group = "verification"
    dependsOn("qualityCheck", "dependencyCheckAnalyze")
}

// Make 'check' task run quality checks
tasks.check {
    dependsOn("qualityCheck")
}
