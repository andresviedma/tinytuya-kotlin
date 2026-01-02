plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
}

group = "andresviedma"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-network:2.3.13")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.83")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("io.github.oshai:kotlin-logging:7.0.13")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}
