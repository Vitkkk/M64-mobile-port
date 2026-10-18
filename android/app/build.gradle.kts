plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.m64port"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.m64port"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // arm64 para celulares; x86_64 para o emulador do Android Studio.
            // Para compilar só uma: ./gradlew assembleDebug -Pm64.abis=arm64-v8a
            val abis = (project.findProperty("m64.abis") as String?)?.split(",")
                ?: listOf("arm64-v8a", "x86_64")
            abiFilters += abis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Bibliotecas nativas comprimidas no APK: o arquivo cai de ~44 MB para
    // bem menos, o que facilita baixar e instalar. O Android as extrai na
    // instalação.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // O APK nunca empacota ROMs, mesmo que alguém as coloque em assets/ por engano.
    androidResources {
        ignoreAssetsPattern = "!*.z64:!*.n64:!*.v64:!*.rom"
    }
}
