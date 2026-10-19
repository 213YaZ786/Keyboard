import com.android.build.api.variant.ApplicationVariant
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0"
}

// whisper.cpp, the engine that writes dictation out: see fetchWhisper below (SMS's app/build.gradle.kts, same pins).
val whisperVersion = "1.9.4"
val whisperSha = "57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae"
val whisperRoot = layout.buildDirectory.dir("whisper").get().asFile
val whisperSrc = File(whisperRoot, "whisper.cpp-$whisperVersion")

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.keyboard"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        // c++_shared since the app has several native libraries (the dictionary engine and whisper.cpp): one runtime for
        // them all, as the NDK's guide says https://developer.android.com/ndk/guides/cpp-support#static_runtimes
        // (HeliBoard's Application.mk had c++_static for its one library); WHISPER_SRC is where fetchWhisper unpacks the source
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared", "-DWHISPER_SRC=${whisperSrc.absolutePath}")
            }
        }
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // Authorities follow the application id, so this app installs beside HeliBoard and its debug build.
        resValue("string", "clipboard_provider_authority", "com.yaz.keyboard.clipprovider")
        resValue("string", "gesture_data_provider_authority", "com.yaz.keyboard.provider")
        resValue("string", "animated_provider_authority", "com.yaz.keyboard.animated") // Keyboard: animated emoji sent as images
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("keyboard.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("keyboard.storePassword").orNull
                keyAlias = providers.gradleProperty("keyboard.keyAlias").orNull
                keyPassword = providers.gradleProperty("keyboard.keyPassword").orNull
            }
        }
    }

    buildTypes {
        release {
            // Only attached when the signing properties are supplied, so a
            // local build without a keystore still produces an unsigned APK.
            if (providers.gradleProperty("keyboard.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        create("nouserlib") { // same as release, but does not allow the user to provide a library
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = false
            isJniDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "clipboard_provider_authority", "com.yaz.keyboard.debug.clipprovider")
            resValue("string", "gesture_data_provider_authority", "com.yaz.keyboard.debug.provider")
            resValue("string", "animated_provider_authority", "com.yaz.keyboard.debug.animated")
        }
        create("runTests") { // build variant for running tests on CI that skips tests known to fail
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        create("debugNoMinify") { // for faster builds in IDE
            isDebuggable = true
            isMinifyEnabled = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            isDefault = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
        resValues = true
        aidl = true // Translator's service interface (kit translate), https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/BuildFeatures#aidl()
    }

    externalNativeBuild {
        // CMake, as Scribe-Android (app/build.gradle.kts): ndk-build cannot take a path with a space
        cmake {
            path = File("src/main/jni/CMakeLists.txt")
        }
    }
    ndkVersion = "28.2.13676358"

    packaging {
        jniLibs {
            // shrinks APK by 3 MB, zipped size unchanged
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // see https://github.com/HeliBorg/HeliBoard/issues/477
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "helium314.keyboard.latin"
    lint {
        abortOnError = true
    }
}

dependencies {
    // androidx
    implementation("androidx.core:core-ktx:1.17.0") // 1.18.0 requires minSdk 23
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4") // the update kit's ProcessLifecycleOwner, Modules' catalogue version

    // kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.bumptech.glide:gifdecoder:5.0.9") // run only inside the isolated DecoderService (safemedia kit), Modules' catalogue version

    // compose
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // versions of Modules' catalogue (shared/base/root/gradle/libs.versions.toml), minSdk 31
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.graphics:graphics-shapes:1.1.0") // Modules' EmptyZone and SunnyShape
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    "debugNoMinifyImplementation"("androidx.compose.ui:ui-tooling")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("sh.calvin.reorderable:reorderable:3.1.0") // for easier re-ordering
    implementation("com.github.skydoves:colorpicker-compose:1.1.3") // for user-defined colors, newer requires minSdk 23
    // Easter eggs (point 21): Konfetti's View version (ISC, https://github.com/DanielMartinus/Konfetti), 2.0.5 of November 2024:
    // later versions are too recent. Reads nothing, sends nothing; its core module comes with it.
    implementation("nl.dionsegijn:konfetti-xml:2.0.5")

    // test
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("androidx.test:core:1.7.0")
}

// whisperVersion and its source's checksum are pinned at the top.
// The engine that writes dictation out: whisper.cpp (MIT, github.com/ggml-org/whisper.cpp), its source fetched at the
// pinned release and checked against its SHA-256, then compiled here; the speech model itself is fetched by the app,
// only when the user asks for it. Copied from SMS's app/build.gradle.kts (fetchWhisper).
val fetchWhisper by tasks.registering {
    val root = whisperRoot
    val src = whisperSrc
    val version = whisperVersion
    val sha = whisperSha
    inputs.property("version", version)
    outputs.dir(src)
    doLast {
        if (File(src, "CMakeLists.txt").exists()) return@doLast
        val bytes = URI("https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$version.tar.gz").toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha) { "whisper.cpp: checksum does not match" }
        root.mkdirs()
        val archive = File(root, "whisper.tar.gz").apply { writeBytes(bytes) }
        val tar = ProcessBuilder("tar", "xzf", archive.path, "-C", root.path).inheritIO().start()
        check(tar.waitFor() == 0) { "whisper.cpp: could not unpack" }
        archive.delete()
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(fetchWhisper)
}
