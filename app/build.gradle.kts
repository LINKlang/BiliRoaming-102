import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.lsplugin.resopt)
    alias(libs.plugins.lsplugin.jgit)
    alias(libs.plugins.lsplugin.apksign)
    alias(libs.plugins.lsplugin.apktransform)
    alias(libs.plugins.lsplugin.cmaker)
}

val appVerCode = jgit.repo()?.commitCount("HEAD") ?: 0
val appVerName: String by rootProject

apksign {
    storeFileProperty = "releaseStoreFile"
    storePasswordProperty = "releaseStorePassword"
    keyAliasProperty = "releaseKeyAlias"
    keyPasswordProperty = "releaseKeyPassword"
}

apktransform {
    copy {
        when (it.buildType) {
            "release" -> file("${it.name}/BiliRoaming_${appVerName}.apk")
            else -> null
        }
    }
}

cmaker {
    default {
        targets("biliroaming")
        abiFilters("armeabi-v7a", "arm64-v8a", "x86")
        arguments += arrayOf(
            "-DANDROID_STL=none",
            "-DCMAKE_CXX_STANDARD=23",
            "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
            "-DCMAKE_MAKE_PROGRAM=D:/Microsoft Visual Studio/2022/Community/Common7/IDE/CommonExtensions/Microsoft/CMake/Ninja/ninja.exe",
        )
        cFlags += "-flto"
        cppFlags += "-flto"
    }

    buildTypes {
        arguments += "-DDEBUG_SYMBOLS_PATH=${layout.buildDirectory.file("symbols/${it.name}").get().asFile.absolutePath}"
    }
}

android {
    namespace = "me.iacn.biliroaming"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"

    buildFeatures {
        prefab = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "me.iacn.biliroaming"
        minSdk = 26
        targetSdk = 35  // Target Android U
        versionCode = appVerCode
        versionName = appVerName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.addAll(
                "-Xno-param-assertions",
                "-Xno-call-assertions",
                "-Xno-receiver-assertions",
                "-language-version=2.0",
            )
        }
    }

    sourceSets {
        named("main") {
            proto {
                srcDir("src/main/proto")
                include("**/*.proto")
            }
        }
    }

    packaging {
        resources {
            excludes += "**"
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        additionalParameters += arrayOf("--allow-reserved-package-id", "--package-id", "0x23")
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "4.1.0+"
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

configurations.all {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    compileOnly(libs.xposed)
    compileOnly(libs.xposed.annotation)
    implementation(libs.xposed.service)
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.java)
    compileOnly(libs.protobuf.protoc)
    implementation(libs.kotlin.coroutines.android)
    implementation(libs.kotlin.coroutines.jdk)
    implementation(libs.androidx.documentfile)
    implementation(libs.cxx)
    implementation(libs.okhttp)
}

fun adbPath(): String {
    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.readLines()?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter('=')
    requireNotNull(sdkDir) { "找不到 Android SDK：請設定 ANDROID_HOME 或 local.properties 的 sdk.dir" }
    val isWindows = System.getProperty("os.name").startsWith("Windows")
    return File(sdkDir, "platform-tools/adb${if (isWindows) ".exe" else ""}").absolutePath
}

val restartBiliBili = tasks.register("restartBiliBili") {
    doLast {
        val adb = adbPath()
        ProcessBuilder(adb, "shell", "am", "force-stop", "tv.danmaku.bili")
            .inheritIO().start().waitFor()
        ProcessBuilder(
            adb,
            "shell",
            "am",
            "start",
            "-n",
            "\$(pm resolve-activity --components tv.danmaku.bili)"
        ).inheritIO().start().waitFor()
    }
}

afterEvaluate {
    tasks.matching { it.name == "installDebug" }.configureEach {
        finalizedBy(restartBiliBili)
    }
}
