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

val pekkoVersion = "1.1.2"
val pekkoHttpVersion = "1.1.0"
val jacksonVersion = "2.17.0"

dependencies {
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.wiremock:wiremock:3.4.2")
    testImplementation("org.awaitility:awaitility:4.2.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ig.tfl.TflApplication")
}

tasks.test {
    useJUnitPlatform()
}
