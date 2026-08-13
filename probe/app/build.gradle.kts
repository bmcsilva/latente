plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.bmcsilva.latente.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bmcsilva.latente.probe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // A sonda usa reflexão sobre CameraMetadata para nomear constantes.
            // Manter a ofuscação desligada enquanto for ferramenta de diagnóstico.
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

// Sem dependências de runtime: só a plataforma e a stdlib do Kotlin.
// Em testes usa-se JUnit, porque os escritores de relatório são Kotlin puro e correm na JVM.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
