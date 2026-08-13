plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.bmcsilva.latente"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bmcsilva.latente"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-f1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Sem dependências de runtime. Camera2, DngCreator e MediaStore são da plataforma.
// A UI da F1 é em Views puros: é andaime de verificação, substituído em F3/F4 pelo visor real.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
