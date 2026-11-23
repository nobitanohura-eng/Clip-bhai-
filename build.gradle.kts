// ClipAI v2 — root build file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // TODO-verify on first GitHub Actions run: confirm these plugin versions
    // still resolve (AGP / Kotlin move fast). Pin exact versions here; do not
    // float them.
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}

// Shared JVM target for any JVM (non-Android) compilation in this project.
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}
