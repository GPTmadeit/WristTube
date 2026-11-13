plugins {
    // AGP 9 carries Kotlin itself. The Compose compiler plugin is still separate.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
