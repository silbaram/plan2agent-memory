import org.gradle.api.GradleException
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.github.silbaram.plan2agent"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.google.cloud.sql:postgres-socket-factory:1.28.6")
    implementation("org.springframework.ai:spring-ai-transformers")
    runtimeOnly("com.microsoft.onnxruntime:onnxruntime")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val onnxVerificationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

val p2aCliIntegrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations.named(onnxVerificationTest.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(onnxVerificationTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

configurations.named(p2aCliIntegrationTest.implementationConfigurationName) {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named(p2aCliIntegrationTest.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.testRuntimeOnly.get())
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("onnx-verification")
    }
}

tasks.register<Test>("onnxVerificationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    maxHeapSize = "2g"
    description = """
        Runs the opt-in verification against operator-provided local ONNX artifacts.
        The model and tokenizer are never downloaded by this task, but a new DJL runtime cache can
        require a one-time application-level native-runtime download before an offline operator run.
        Requires P2A_ONNX_MODEL_URI and P2A_ONNX_TOKENIZER_URI file URI values whose bytes match
        the pinned V2 SHA-256 checksums. Example: P2A_ONNX_MODEL_URI=file:///path/model.onnx
        P2A_ONNX_TOKENIZER_URI=file:///path/tokenizer.json ./gradlew onnxVerificationTest
    """.trimIndent()
    testClassesDirs = onnxVerificationTest.output.classesDirs
    classpath = onnxVerificationTest.runtimeClasspath
    shouldRunAfter(tasks.named<Test>("test"))
    useJUnitPlatform {
        includeTags("onnx-verification")
    }
    doFirst {
        val missing = ONNX_VERIFICATION_ENVIRONMENT_NAMES.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "onnxVerificationTest requires ${missing.joinToString()} as file URI values. " +
                    "Set P2A_ONNX_MODEL_URI=file:///path/model.onnx and " +
                    "P2A_ONNX_TOKENIZER_URI=file:///path/tokenizer.json; " +
                    "the task validates both against the pinned V2 SHA-256 checksums.",
            )
        }
    }
}

tasks.register<Test>("p2aCliIntegrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the actual Plan2Agent CLI against a RANDOM_PORT Memory server and PostgreSQL Testcontainer."
    testClassesDirs = p2aCliIntegrationTest.output.classesDirs
    classpath = p2aCliIntegrationTest.runtimeClasspath
    shouldRunAfter(tasks.named<Test>("test"))
    useJUnitPlatform()
    doNotTrackState("Executes an external P2A CLI against a Docker-backed Memory server.")
    doFirst {
        val scriptPath = System.getenv("P2A_CLI_SCRIPT")?.trim().orEmpty()
        if (scriptPath.isEmpty()) {
            throw GradleException(
                "p2aCliIntegrationTest requires P2A_CLI_SCRIPT to point to the actual Plan2Agent scripts/p2a.mjs file.",
            )
        }
        val scriptFile = file(scriptPath)
        if (!scriptFile.isFile) {
            throw GradleException(
                "p2aCliIntegrationTest requires P2A_CLI_SCRIPT to be a file: ${scriptFile.absolutePath}",
            )
        }

        val nodeVersion = try {
            val process = ProcessBuilder("node", "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.waitFor() != 0) {
                throw GradleException("Node.js version check failed: $output")
            }
            output
        } catch (error: java.io.IOException) {
            throw GradleException("p2aCliIntegrationTest requires Node.js 22 or newer on PATH.", error)
        }
        val nodeMajor = Regex("^v?(\\d+)").find(nodeVersion)?.groupValues?.get(1)?.toIntOrNull()
        if (nodeMajor == null || nodeMajor < 22) {
            throw GradleException(
                "p2aCliIntegrationTest requires Node.js 22 or newer; found ${nodeVersion.ifEmpty { "unknown" }}.",
            )
        }
    }
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
}

private val ONNX_VERIFICATION_ENVIRONMENT_NAMES = listOf(
    "P2A_ONNX_MODEL_URI",
    "P2A_ONNX_TOKENIZER_URI",
)
