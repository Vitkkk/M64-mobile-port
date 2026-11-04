plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// O Client ID do Azure para o login Microsoft (público, não é segredo).
// ./gradlew assembleDebug -Pzenith.msaClientId=<GUID>  ou a variável ZENITH_MSA_CLIENT_ID.
// Sem ele o app ainda roda, e o jogador pode informar um Client ID nas configurações.
val msaClientId: String = (project.findProperty("zenith.msaClientId") as String?)
    ?: System.getenv("ZENITH_MSA_CLIENT_ID") ?: ""

android {
    namespace = "io.github.zenith"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.zenith"
        // API 28: AAudio e Vulkan 1.1, que o Horizon (wgpu + cpal) usa.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "MSA_CLIENT_ID", "\"$msaClientId\"")
        // A versão do Minecraft que o Horizon fixado reimplementa.
        buildConfigField("String", "GAME_VERSION", "\"26.3\"")
        buildConfigField("String", "HORIZON_COMMIT", "\"fc02103818c212920fd24aa34a2b2f91ba49e56f\"")

        ndk {
            // arm64 para celulares; x86_64 para o emulador. A .so vem de tools/build-native.sh.
            val abis = (project.findProperty("zenith.abis") as String?)?.split(",")
                ?: listOf("arm64-v8a", "x86_64")
            abiFilters += abis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave de debug para que o APK de release do CI seja instalável.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // Comprimida no APK: a libhorizon_android.so tem dezenas de MB.
            useLegacyPackaging = true
        }
    }

    // O APK nunca leva arquivos do Minecraft, mesmo se alguém os colocar em assets/ por engano.
    androidResources {
        ignoreAssetsPattern = "!*.jar:!*.ogg:!horizon-resources.json:!*.mca"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // ./gradlew testDebugUnitTest -Pzenith.screenshots grava PNGs das telas em app/build/screens/
            if (project.hasProperty("zenith.screenshots")) it.systemProperty("zenith.screenshots", "1")
            // O Robolectric baixa o android-all do Maven Central; -Pzenith.mavenMirror=URL usa um espelho.
            (project.findProperty("zenith.mavenMirror") as String?)?.let { url ->
                it.systemProperty("robolectric.dependency.repo.url", url)
            }
        }
    }
}

dependencies {
    // A janela do jogo (o winit do Horizon usa android-activity com GameActivity 4.4).
    implementation("androidx.games:games-activity:4.4.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
