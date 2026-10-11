plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget()
    js(IR) {
        browser()
        useEsModules()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    jvmToolchain(17)
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
        }
        androidMain.dependencies {
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
        }
        jsMain.dependencies { implementation(compose.html.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "com.shelest.ui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
