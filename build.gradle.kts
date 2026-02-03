plugins {
    java
    application
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

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e", "container")
    }
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
