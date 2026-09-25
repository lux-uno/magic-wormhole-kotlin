import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mavenPublish)
}

// Needed so that composite builds (includeBuild) can substitute io.github.anima-lux-uno:wormhole.
group = property("GROUP").toString()
version = property("VERSION_NAME").toString()

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    iosArm64()
    iosSimulatorArm64()

    compilerOptions {
        optIn.add("kotlin.ExperimentalStdlibApi")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.network)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlincrypto.hmac.sha2)
            implementation(libs.kotlincrypto.random)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

tasks.withType<Test>().configureEach {
    // Interop tests talk to the public relay and the `wormhole` CLI. They only run when asked.
    System.getenv("WORMHOLE_INTEROP")?.let { environment("WORMHOLE_INTEROP", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

mavenPublishing {
    // Coordinates come from GROUP and VERSION_NAME in gradle.properties; artifact id = "wormhole".
    pom {
        name = "wormhole-kotlin"
        description = "A Kotlin Multiplatform implementation of the Magic Wormhole protocol."
        inceptionYear = "2026"
        url = "https://github.com/anima-lux-uno/wormhole-kotlin"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "anima-lux-uno"
                name = "anima-lux-uno"
                url = "https://github.com/anima-lux-uno"
            }
        }
        scm {
            url = "https://github.com/anima-lux-uno/wormhole-kotlin"
            connection = "scm:git:git://github.com/anima-lux-uno/wormhole-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/anima-lux-uno/wormhole-kotlin.git"
        }
    }
    // Maven Central upload and signing are enabled when the credentials exist:
    // publishToMavenCentral(); signAllPublications()
}
