@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.publishOnCentral)
    alias(libs.plugins.dokka)
    `java-library`
    jacoco
}

repositories {
    mavenCentral()
}

group = "io.github.andresviedma.tinytuya"
val libVersion: String by project
version = libVersion

kotlin {
    jvmToolchain(1_8)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("io.ktor:ktor-network:2.3.13")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.83")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("io.github.oshai:kotlin-logging:7.0.13")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.23")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishOnCentral {
    val repoOwner = "andresviedma"
    projectLongName.set("TinyTuya-kotlin")
    projectDescription.set("Kotlin port of tinytuya, library to interface with Tuya WiFi smart devices")
    scmConnection.set("scm:git:https://github.com/$repoOwner/${rootProject.name}")
    projectUrl.set("https://github.com/$repoOwner/${rootProject.name}")
    licenseName.set("Apache License 2.0")
    licenseUrl.set("https://www.apache.org/licenses/LICENSE-2.0")
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        developers {
            developer {
                id.set("andresviedma")
                name.set("Andrés Viedma")
                email.set("andres.viedma@gmail.com")
            }
        }
    }
}
