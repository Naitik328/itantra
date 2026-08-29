// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    id("com.android.application") version "9.3.0" apply false
    // AGP 9.0+ has built-in Kotlin support, so the kotlin.android plugin is gone.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
