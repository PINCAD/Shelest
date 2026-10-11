import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Ключи внешних API лежат в secrets.properties (вне Git). Нет файла или ключа - пустая строка:
// сборка проходит, а соответствующий каталог просто не отвечает.
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
}
fun secret(name: String): String =
    "\"" + secrets.getProperty(name, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.lexiread.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lexiread.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 24
        versionName = "1.4.20"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "YANDEX_DICTIONARY_API_KEY", secret("YANDEX_DICTIONARY_API_KEY"))
        buildConfigField("String", "GOOGLE_BOOKS_API_KEY", secret("GOOGLE_BOOKS_API_KEY"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
        }
    }

    // The exported schemas, on the device, so a migration test can open a real database at an
    // old version and run the migration against it.
    sourceSets.getByName("androidTest").assets.srcDirs("$projectDir/schemas")
}

// Room writes the schema of every version it compiles to app/schemas. Without it the database
// is only ever described by the current entity classes, so nothing can check that a
// hand-written migration actually produces the table Room expects — the mismatch shows up as a
// crash on a reader's device instead. See AppDatabase.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Include a newly generated Room schema in the test APK in the same build.
tasks.configureEach {
    if (name == "mergeDebugAndroidTestAssets") dependsOn("kspDebugKotlin")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":shared-ui"))
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")

    // Core
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Retrofit + OkHttp (Wikipedia API)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room (books library + definition cache)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // PdfBox-Android: the text layer of a PDF. Androids own PdfRenderer draws pages and
    // cannot read them, so search, bookmarks and the voice all need this.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Jsoup (EPUB/HTML parsing)
    implementation("org.jsoup:jsoup:1.17.2")

    // Coil (book covers)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Accompanist (permissions)
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
