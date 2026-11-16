import java.io.File

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.3.21"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

ktlint {
    filter {
        exclude("**/build/**")
        exclude("**/*.kts")
    }
}

group = "com.jacobcraig"
version = "0.0.1-SNAPSHOT"
description = "Stridehearth"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Only /actuator/health is exposed: the container image's HEALTHCHECK polls it (ADR-0009).
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0") // Use latest 5.x version
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Source: https://mvnrepository.com/artifact/com.garmin/fit
    implementation("com.garmin:fit:21.217.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.6")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // `./gradlew test -PupdateOpenApiSnapshot --tests '*OpenApiContractTest'` rewrites frontend/openapi.json.
    if (project.hasProperty("updateOpenApiSnapshot")) {
        systemProperty("openapi.snapshot.update", "true")
    }
    // `./gradlew test -PregenerateSampleRun --tests '*SampleRunGeneratorTest'` rewrites the synthetic FIT fixtures (#126).
    if (project.hasProperty("regenerateSampleRun")) {
        systemProperty("sample.run.regenerate", "true")
    }
    // Files read by OpenApiContractTest and the ci/git/lint guardrail tests, so editing them re-runs those tests.
    inputs.files(
        "frontend/openapi.json",
        "frontend/package.json",
        ".github/workflows/ci.yml",
        ".githooks/pre-push",
    )
}

// The Heatmap performance harness (#110) is tagged `performance`: `test` (and so `check`) leaves it out, and
// `performanceTest` runs only it. Its ~300-Activity smoke version is an ordinary test and runs in `check`.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("performance") }
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs the Heatmap performance harness: ~10k synthetic Activities in Testcontainers PostGIS (not part of check)"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("performance") }
    maxHeapSize = "3g"
    // `-PheatmapActivities=N` seeds N Activities instead of 10,000.
    project.findProperty("heatmapActivities")?.let { systemProperty("heatmap.performance.activities", it.toString()) }
    testLogging.showStandardStreams = true
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.named("test"))
}

val installGitHooks =
    tasks.register<Exec>("installGitHooks") {
        group = "verification"
        description = "Configures git to use repository-tracked hooks from .githooks"
        commandLine("git", "config", "core.hooksPath", ".githooks")
        onlyIf { file(".git").exists() }
        doFirst {
            val hookFile = file(".githooks/pre-push")
            if (hookFile.exists()) {
                hookFile.setExecutable(true)
            }
        }
    }

fun resolveNpm(): String {
    val homeDir = System.getProperty("user.home") ?: ""
    val pathEnv = System.getenv("PATH") ?: ""
    val pathCandidates = pathEnv.split(File.pathSeparatorChar).map { "$it/npm" }
    val nvmVersions = File("$homeDir/.nvm/versions/node").listFiles()
    val nvmCandidates = nvmVersions?.map { "${it.absolutePath}/bin/npm" } ?: emptyList()
    val standardCandidates =
        listOf(
            "/opt/homebrew/bin/npm",
            "/usr/local/bin/npm",
            "/usr/bin/npm",
        )
    val candidates = mutableListOf<String>()
    candidates.addAll(pathCandidates)
    candidates.addAll(nvmCandidates)
    candidates.addAll(standardCandidates)
    for (candidate in candidates) {
        val f = File(candidate)
        if (f.exists() && f.canExecute()) {
            return f.absolutePath
        }
    }
    return "npm"
}

fun configureNpmEnvironment(exec: Exec) {
    val npmPath = resolveNpm()
    val f = File(npmPath)
    if (f.isAbsolute && f.parentFile != null) {
        val binDir = f.parentFile.absolutePath
        val currentPath = System.getenv("PATH") ?: ""
        exec.environment("PATH", "$binDir${File.pathSeparator}$currentPath")
    }
}

val npmInstall =
    tasks.register<Exec>("npmInstall") {
        group = "build"
        description = "Installs frontend npm dependencies"
        workingDir = file("frontend")
        configureNpmEnvironment(this)
        inputs.file("frontend/package.json")
        inputs.file("frontend/package-lock.json")
        outputs.dir("frontend/node_modules")
        commandLine(resolveNpm(), "ci")
    }

/** Registers an `npm run <script>` task in frontend/ that runs after dependencies are installed. */
fun registerNpmScript(
    name: String,
    group: String,
    description: String,
    vararg npmArgs: String,
    configure: Exec.() -> Unit = {},
) = tasks.register<Exec>(name) {
    this.group = group
    this.description = description
    dependsOn(npmInstall)
    workingDir = file("frontend")
    configureNpmEnvironment(this)
    commandLine(listOf(resolveNpm()) + npmArgs)
    configure()
}

val frontendLint = registerNpmScript("frontendLint", "verification", "Runs Biome lint + format check (biome check)", "run", "lint")
val frontendFormat = registerNpmScript("frontendFormat", "formatting", "Applies Biome formatting to the frontend workspace", "run", "format")
val frontendTypecheck = registerNpmScript("frontendTypecheck", "verification", "Typechecks the frontend (tsc --noEmit)", "run", "typecheck")
val frontendTest = registerNpmScript("frontendTest", "verification", "Runs Vitest tests (vitest run)", "test")

// Fails when frontend/src/api/generated differs from codegen of the committed frontend/openapi.json snapshot.
// OpenApiContractTest separately fails when that snapshot differs from the live backend spec.
// -PfrontendGeneratedDir=<dir> checks another directory instead (used by the guardrail tests).
val frontendCodegenCheck =
    registerNpmScript(
        "frontendCodegenCheck",
        "verification",
        "Verifies the generated API client is fresh versus frontend/openapi.json",
        "run",
        "codegen:check",
    ) {
        project.findProperty("frontendGeneratedDir")?.let { args("--", "--generated", it.toString()) }
    }

val frontendBuild =
    registerNpmScript("frontendBuild", "build", "Compiles TypeScript and builds the Vite bundle", "run", "build") {
        inputs.dir("frontend/src")
        inputs.files(
            "frontend/index.html",
            "frontend/package-lock.json",
            "frontend/tsconfig.json",
            "frontend/vite.config.ts",
        )
        outputs.dir("frontend/dist")
    }

val frontendCheck =
    tasks.register("frontendCheck") {
        group = "verification"
        description = "Runs Biome, tsc --noEmit, Vitest, the codegen freshness check, and the production build"
        dependsOn(frontendLint, frontendTypecheck, frontendTest, frontendCodegenCheck, frontendBuild)
    }

// Package the SPA bundle as classpath static resources, so it lands in BOOT-INF/classes/static of bootJar
// (and is available to tests and bootRun) without racing other writers of build/resources/main.
tasks.named<ProcessResources>("processResources") {
    from(frontendBuild) { into("static") }
}

tasks.named("compileKotlin") {
    dependsOn(installGitHooks)
}
tasks.named("ktlintCheck") {
    dependsOn(installGitHooks)
}
tasks.named("check") {
    dependsOn(installGitHooks)
    dependsOn(tasks.named("ktlintCheck"))
    dependsOn(frontendCheck)
}
tasks.named("bootJar") {
    dependsOn(frontendCheck)
}
