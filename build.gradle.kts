
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
