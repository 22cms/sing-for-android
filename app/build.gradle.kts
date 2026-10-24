import com.android.build.api.variant.FilterConfiguration
import java.io.FileInputStream
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateEngineVersion : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val escaped = versionName.get().replace("\\", "\\\\").replace("\"", "\\\"")
        val source = outputDirectory.file("org/francasio/sing/provider/EngineVersion.kt").get().asFile
        source.parentFile.mkdirs()
        source.writeText("""
            package org.francasio.sing.provider

            // Generated from app/build.gradle.kts versionName. Inlined into the
            // injected engine so LSPatch reports its embedded build, not the installed app.
            internal const val ENGINE_VERSION = "$escaped"
        """.trimIndent() + "\n")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

android {
    namespace = "org.francasio.sing"
    compileSdk = 34
    ndkVersion = "28.0.12674087"

    defaultConfig {
        applicationId = "org.francasio.sing"
        // Android 11 (API 30) minimum SDK to match Apple Music's minimum SDK version
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cFlags.addAll(listOf("-O3", "-ffast-math", "-funroll-loops", "-fvisibility=hidden"))
                cppFlags.addAll(listOf("-std=c++17", "-O3", "-ffast-math", "-funroll-loops", "-fvisibility=hidden"))
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("releaseEnv") {
            // Check both root project and app/ folder in case it was saved in either place
            val candidates = listOf(
                rootProject.file("keystore.properties"),
                file("keystore.properties")
            )
            val keystorePropertiesFile = candidates.firstOrNull { it.exists() }

            if (keystorePropertiesFile != null) {
                val properties = Properties().apply {
                    keystorePropertiesFile.inputStream().use { load(it) }
                }
                val fileProp = properties.getProperty("storeFile")?.trim()
                if (!fileProp.isNullOrBlank()) {
                    // Resolve keystore file relative to root or app/
                    val resolvedFile = listOf(
                        rootProject.file(fileProp),
                        file(fileProp)
                    ).firstOrNull { it.exists() } ?: rootProject.file(fileProp)

                    storeFile = resolvedFile
                    storePassword = properties.getProperty("storePassword")?.trim()
                    keyAlias = properties.getProperty("keyAlias")?.trim()
                    keyPassword = properties.getProperty("keyPassword")?.trim()

                    println(">>> [SIGNING SUCCESS] Using keystore: ${resolvedFile.absolutePath} (exists: ${resolvedFile.exists()})")
                } else {
                    println(">>> [SIGNING WARNING] 'storeFile' property is missing or blank inside ${keystorePropertiesFile.absolutePath}")
                }
            } else {
                println(">>> [SIGNING WARNING] keystore.properties NOT found in root or app/ folder! Falling back to Debug.")
                println(">>> Checked paths:")
                candidates.forEach { println(">>>   - ${it.absolutePath}") }
            }
        }
    }

    val envSigning = signingConfigs.getByName("releaseEnv")
    val chosenSigning = if (envSigning.storeFile != null) envSigning else signingConfigs.getByName("debug")

    buildTypes {
        getByName("debug") {
            signingConfig = chosenSigning
            isCrunchPngs = false
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = chosenSigning
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-Xbackend-threads=0")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Native libs must be STORED (uncompressed) in the APK: when this
            // module is embedded by LSPatch, the host process dlopens
            // libsing_dsp.so straight from the module APK path.
            useLegacyPackaging = false
            excludes += "**/libQnnGpu.so"
            excludes += "**/libQnnDsp*.so"
            // ONNX Runtime + QNN accelerator are downloaded on demand (see
            // NativeLibDownloader) instead of being shipped in the APK.
            excludes += "**/libonnxruntime*.so"
            excludes += "**/libQnn*.so"
            excludes += "**/libc++.so"
            excludes += "**/libcdsprpc.so"
            excludes += "**/libvmmem.so"
            excludes += "**/vendor.qti.hardware.dsp@1.0.so"
            excludes += "**/libbase.so"
            excludes += "**/libdmabufheap.so"
            excludes += "**/libcutils.so"
            excludes += "**/libvndksupport.so"
            excludes += "**/libutils.so"
            excludes += "**/libhardware.so"
            excludes += "**/libhidlbase.so"
            // Vendor DSP libs shipped for the QNN HTP transport: keep them
            // byte-identical (do not strip) - they are prebuilt vendor blobs.
            keepDebugSymbols.addAll(listOf(
                "**/libcdsprpc.so",
                "**/libvmmem.so",
                "**/vendor.qti.hardware.dsp@1.0.so",
                "**/libc++.so",
                "**/ld-android.so",
                "**/libdl_android.so",
                "**/libbase.so",
                "**/libdmabufheap.so",
                "**/libcutils.so",
                "**/libvndksupport.so",
                "**/libutils.so",
                "**/libhardware.so",
                "**/libhidlbase.so"
            ))
        }
    }
}

// Assign a priority integer to each ABI (higher number = preferred)
val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86_64" to 3
)

androidComponents {
    onVariants { variant ->
        val generateEngineVersion = tasks.register<GenerateEngineVersion>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}EngineVersion"
        ) {
            versionName.set(android.defaultConfig.versionName!!)
            outputDirectory.set(layout.buildDirectory.dir("generated/engineVersion/${variant.name}"))
        }
        kotlin.sourceSets.getByName(variant.name).kotlin.srcDir(generateEngineVersion)

        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier

            val abiPriority = abiCodes[abi] ?: 0

            // Appends the ABI code to the end of the base versionCode (e.g. 1 -> 1001, 1002, etc.)
            val baseCode = output.versionCode.get() ?: 1
            output.versionCode.set(baseCode * 1000 + abiPriority)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Vector 2.2 and LSPatch 1.2 provide libxposed API 102 at runtime. Keep compileOnly.
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.51.0")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha01")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

tasks.withType<com.android.build.gradle.internal.tasks.CheckAarMetadataTask> {
    enabled = false
}
