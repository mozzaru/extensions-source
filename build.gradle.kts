import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.shadowjar) apply false
}

allprojects {
    group = "tachiyomi"
    version = "1.0"

    repositories {
        mavenCentral()
        maven("https://maven.google.com/")
        maven("https://jitpack.io")
        maven("https://dl.google.com/dl/android/maven2/")
        maven("https://repo1.maven.org/maven2/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
}

val projects = listOf(
    project(":AndroidCompat"),
    project(":AndroidCompat:Config"),
    project(":inspector")
)

configure(projects) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_17.toString()
        }
    }

    dependencies {
        implementation(rootProject.libs.bundles.common)
    }
}
