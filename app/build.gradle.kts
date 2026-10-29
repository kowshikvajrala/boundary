import java.util.Locale
import java.util.Properties

// The app's identity. Changing either after publishing makes it a different app.
val appId = "app.boundary.blocker"
val appName = "Boundary"

// Release signing secrets live outside git in keystore.properties at the repo root:
// storeFile, storePassword, keyAlias, keyPassword.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// The fastlane changelogs are the single source of truth: the store listings, the
// GitHub release body and the in-app "what's new" dialog all read the same files.
// They are copied into assets/changelogs/<versionCode>.txt at build time.
abstract class CopyFastlaneChangelogsTask : DefaultTask() {
    @get:InputDirectory
    abstract val changelogs: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    @TaskAction
    fun copyChangelogs() {
        val target = assetsDir.get().dir("changelogs").asFile
        target.deleteRecursively()
        target.mkdirs()
        changelogs.get().asFile.listFiles()
            ?.filter { it.extension == "txt" }
            ?.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val copyFastlaneChangelogs = tasks.register<CopyFastlaneChangelogsTask>("copyFastlaneChangelogs") {
    changelogs.set(rootProject.layout.projectDirectory.dir("fastlane/metadata/android/en-US/changelogs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyFastlaneChangelogs,
            CopyFastlaneChangelogsTask::assetsDir
        )
    }
}

android {
    namespace = "neth.iecal.curbox"
    compileSdk = 35

    lint {
        disable.add("NullSafeMutableLiveData")
        abortOnError = false
    }

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "1.14.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", appName)
        resValue("string", "blocker_service_label", "$appName App Blocker")

        buildConfigField("Boolean", "SUPPORTS_UI_HIDER", "true")
        buildConfigField("Boolean", "SUPPORTS_ANTI_UNINSTALL", "true")
        // Holds WRITE_SECURE_SETTINGS (granted once via Shizuku) so grayscale can
        // flip secure settings directly instead of shelling out per toggle.
        buildConfigField("Boolean", "SUPPORTS_WRITE_SECURE_SETTINGS", "true")
        // Cloud sync talks to Curbox's own servers, so this build ships without it.
        buildConfigField("Boolean", "FDROID_VARIANT", "true")
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    splits {
        abi {
            isEnable = false

        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)


    // QR Scanner & Generator
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    
    // Shizuku dependecies

    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.mpandroidchart)
    implementation(libs.timerangepicker)

}
androidComponents {
    // Only the signed release build exists.
    beforeVariants { variant ->
        if (variant.buildType == "debug") variant.enable = false
    }
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        tasks.register("installAndGrantAccessibility$variantName") {
            group = "install"
            description = "Installs the app, grants Accessibility permission, and launches it"
            dependsOn("install$variantName")
            
            doLast {
                val adbPath = sdkComponents.adb.get().asFile.absolutePath
                val appId = variant.applicationId.get()
                Thread.sleep(2000)
                // Grant Accessibility Permission
                exec {
                    val baseId = "neth.iecal.curbox"
                    val services = "$appId/$baseId.services.AppBlockerService"

                    commandLine(adbPath, "shell", "settings", "put", "secure", "enabled_accessibility_services", services)
                }

                // Launch MainActivity
                exec {
                    val baseId = "neth.iecal.curbox"
                    commandLine(adbPath, "shell", "am", "start", "-n", "$appId/$baseId.ui.activity.FragmentActivity")
                }
            }
        }
    }
}
