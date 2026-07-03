plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(projects.shared)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("net.vertexdezign.vdt.server.ServerKt")
}

// Bundle the production wasm app into the server's resources under `static/`, so a single
// server artifact serves both the dashboard and the API/WebSocket.
tasks.named<ProcessResources>("processResources") {
    dependsOn(":app:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("app/build/dist/wasmJs/productionExecutable")) {
        into("static")
    }
}
