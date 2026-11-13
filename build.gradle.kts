
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "net.aprlflwrs"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.websockets)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

ktor {
    fatJar {
        archiveFileName.set("aunthillary.jar")
    }
}

// The browser decoder's tests (src/test/js) run under Node against the bytes the Kotlin tests
// write, so `./gradlew check` verifies both sides of the protocol. Skipped if Node is missing.
val nodeExecutable: File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    .map { File(it, "node") }.firstOrNull { it.canExecute() }

val jsTest = tasks.register<Exec>("jsTest") {
    group = "verification"
    description = "Runs the JavaScript tests in src/test/js with Node."
    dependsOn(tasks.test)
    executable = nodeExecutable?.path ?: "node"
    args("--test", "src/test/js/")
    onlyIf("Node is installed") { nodeExecutable != null }
}
tasks.check { dependsOn(jsTest) }

// Long simulation tests, marked @Category(SlowTests::class) (src/test/kotlin/.../SlowTests.kt),
// are left out of `test` and `check` and run with `./gradlew slowTest`.
val slowCategory = "net.aprlflwrs.aunthillary.SlowTests"
tasks.test {
    useJUnit { excludeCategories(slowCategory) }
}
tasks.register<Test>("slowTest") {
    group = "verification"
    description = "Runs the long simulation tests (category SlowTests), which check leaves out."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnit { includeCategories(slowCategory) }
    shouldRunAfter(tasks.test)
    // The scenario test reads SCENARIO_SEED to try other seeds; changing it must rerun the task.
    inputs.property("scenarioSeed", System.getenv("SCENARIO_SEED") ?: "")
}

// The headless runner (src/main/kotlin/.../Headless.kt): runs the colony without a browser, as
// fast as the CPU allows, and writes pheromone images and a metrics CSV. Not part of check.
//   ./gradlew runHeadless --args="--seed 1 --duration 600 --food 400,300 --food 160,380"
//   ./gradlew runHeadless --args="--lesion"
//   ./gradlew runHeadless --args="--help"
tasks.register<JavaExec>("runHeadless") {
    group = "application"
    description = "Runs the colony without a browser and writes pheromone images and metrics."
    mainClass = "net.aprlflwrs.aunthillary.HeadlessKt"
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = projectDir
    jvmArgs("-Djava.awt.headless=true")
}
