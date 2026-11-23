// ClipAI v2 — native Kotlin scaffold.
// Research verdict (2026-11-23): native Kotlin first; 100% offline clipping
// feasible on 6 GB+ phones. Timings in comments are estimates only —
// flagship 5–10 min, mid-range 15–30 min for a 10-min video — not measurements.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // TODO-verify: Sherpa-ONNX is NOT on Maven Central. Do NOT add a
        // guessed Maven coordinate here. The Sherpa-ONNX Android AAR is
        // distributed via GitHub releases — see app/build.gradle.kts.
        // If a Maven-published Sherpa-ONNX artifact appears, add its repo
        // here only after verifying the coordinates on the first CI run.
    }
}

rootProject.name = "clipai-v2"
include(":app")
