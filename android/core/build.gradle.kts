plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.okhttp.sse)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Monokulo's OpenAPI document at the pinned commit, for MonokuloContractTest.
// Monokulo generates it from its code and never commits it, so this builds
// it (Rust, from Monokulo's source) rather than keep a copy that could drift.
// `-Pmonokulo.openapi=FILE` uses a document already generated instead.
val monokuloSha = providers.gradleProperty("monokulo.sha").get()
val givenOpenApi = providers.gradleProperty("monokulo.openapi").orNull
val repo: File = rootDir.parentFile
val monokuloOpenApi = tasks.register<Exec>("monokuloOpenApi") {
    description = "Generates Monokulo's OpenAPI document at monokulo.sha."
    val out = layout.buildDirectory.file("monokulo/openapi-$monokuloSha.json")
    val checkout = rootProject.layout.buildDirectory.dir("monokulo-src")
    inputs.property("sha", monokuloSha)
    outputs.file(out)
    val generate = givenOpenApi == null
    onlyIf { generate }
    commandLine(
        File(repo, "tools/monokulo-openapi/generate.sh").absolutePath,
        monokuloSha,
        out.get().asFile.absolutePath,
        checkout.get().asFile.absolutePath,
    )
}

tasks.test {
    dependsOn(monokuloOpenApi)
    val openapi = givenOpenApi ?: layout.buildDirectory.file("monokulo/openapi-$monokuloSha.json").get().asFile.absolutePath
    inputs.file(openapi)
    systemProperty("monokulo.openapi", openapi)
}
