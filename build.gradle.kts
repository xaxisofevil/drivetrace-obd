// Top-level build file. Plugin versions are declared here and applied per-module.
// AGP 9+ bundles Kotlin support by default (see
// https://developer.android.com/build/migrate-to-built-in-kotlin), so
// org.jetbrains.kotlin.android is deliberately NOT applied here.
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
}
