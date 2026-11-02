import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
}

group = "io.github.leeturner"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val detektVersion = "2.0.0-alpha.6"

dependencies {
    compileOnly("dev.detekt:detekt-api:$detektVersion")

    testImplementation("dev.detekt:detekt-test:$detektVersion")
    testImplementation("io.arrow-kt:arrow-core:2.3.0.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

tasks.test {
    useJUnitPlatform()
    // Fail on snippets that don't compile, so unresolved Arrow calls can't make "no finding" tests pass
    systemProperty("compile-test-snippets", "true")
}
