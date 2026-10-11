plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    jvm()
    js(IR) {
        browser()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies { api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "com.shelest.shared"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension> {
        download = false
    }
}
