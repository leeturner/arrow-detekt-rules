import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates("io.github.leeturner", "arrow-detekt-rules", version.toString())
    pom {
        name = "Arrow detekt rules"
        description = "A detekt 2 rule set for Arrow code"
        url = "https://github.com/leeturner/arrow-detekt-rules"
        inceptionYear = "2026"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "leeturner"
                name = "Lee Turner"
                url = "https://leeturner.me"
            }
        }
        scm {
            url = "https://github.com/leeturner/arrow-detekt-rules"
            connection = "scm:git:git://github.com/leeturner/arrow-detekt-rules.git"
            developerConnection = "scm:git:ssh://git@github.com/leeturner/arrow-detekt-rules.git"
        }
    }
}
