import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.leeturner"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.detekt.api)

    testImplementation(libs.detekt.test)
    testImplementation(libs.arrow.core)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
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
