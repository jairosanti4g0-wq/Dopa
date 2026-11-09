import java.io.FileInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.dopa.cleaner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dopa.cleaner"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "DOPA_KEYSTORE_PATH")
        if (storePath != null) {
            create("shared") {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "DOPA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DOPA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DOPA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        val shared = signingConfigs.findByName("shared")
        debug {
            if (shared != null) signingConfig = shared
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = shared ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val modelZipUrl = "https://github.com/GantMan/nsfw_model/releases/download/1.1.0/nsfw_mobilenet_v2_140_224.zip"
val modelEntry = "mobilenet_v2_140_224/saved_model.tflite"
val modelSha256 = "6d9271fd927ef46328e8168babeaf4169abed8f5808d79383f448f90c67f36d4"
val modelOut = layout.projectDirectory.file("src/main/assets/models/nsfw_mobilenet_v2_140_224.tflite")

fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

val fetchNsfwModel by tasks.registering {
    description = "Downloads and verifies the NSFW TFLite model (GantMan/nsfw_model, MIT)."
    outputs.file(modelOut)
    doLast {
        val out = modelOut.asFile
        if (out.exists() && sha256(out) == modelSha256) return@doLast
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, "${out.name}.part")
        var found = false
        URI(modelZipUrl).toURL().openStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == modelEntry) {
                        tmp.outputStream().use { zip.copyTo(it) }
                        found = true
                        break
                    }
                }
            }
        }
        if (!found) throw GradleException("Entry $modelEntry not found in $modelZipUrl")
        val actual = sha256(tmp)
        if (actual != modelSha256) {
            tmp.delete()
            throw GradleException("Model checksum mismatch: $actual")
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw GradleException("Could not move model to $out")
    }
}

tasks.named("preBuild") { dependsOn(fetchNsfwModel) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tensorflow.lite)
    implementation(libs.play.services.auth)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
