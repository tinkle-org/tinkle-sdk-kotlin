import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import com.tinkle.build.GenerateInstrumentationKeeps

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tinkle.sdk.smoke"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.tinkle.sdk.smoke"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    buildTypes {
        debug {
            // CI's connectedDebugAndroidTest still exercises R8 + the release AAR.
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "smoke-rules.pro")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "smoke-rules.pro")
        }
    }
    testBuildType = providers.gradleProperty("smokeBuildType").orElse("debug").get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { jvmToolchain(17) }

androidComponents {
    onVariants { variant ->
        variant.androidTest?.let { test ->
            val generate = tasks.register<GenerateInstrumentationKeeps>(
                "generate${variant.name.replaceFirstChar { it.uppercase() }}InstrumentationKeeps"
            ) {
                // Includes compile-only annotations as resolution libraries as well
                // as android.jar. Neither contributes source references or keeps.
                libraries.from(sdkComponents.bootClasspath, test.compileClasspath)
                // JUnit3 adapters in runner reference the SDK's optional test APIs.
                libraries.from(sdkComponents.sdkDirectory.map { sdk ->
                    listOf("base", "mock", "runner").map {
                        sdk.file("platforms/android-${android.compileSdk}/optional/android.test.$it.jar")
                    }
                })
                // monitor 1.8.0 invokes these hidden Instrumentation methods.
                // AndroidX's bridge has no bodies in the AAR and android.jar
                // omits hidden APIs. Exempt only these five exact signatures;
                // every other missing class/member remains an error. See README.
                val bridge = "android.app.Instrumentation\$ActivityResult androidx.test.internal.runner.hidden.ExposedInstrumentationApi.execStartActivity"
                val prefix = "android.content.Context, android.os.IBinder, android.os.IBinder, "
                platformStubMethods.set(listOf(
                    "$bridge(${prefix}android.app.Activity, android.content.Intent, int)",
                    "$bridge(${prefix}android.app.Activity, android.content.Intent, int, android.os.Bundle)",
                    "$bridge(${prefix}android.app.Activity, android.content.Intent, int, android.os.Bundle, android.os.UserHandle)",
                    "$bridge(${prefix}android.app.Fragment, android.content.Intent, int, android.os.Bundle)",
                    "$bridge(${prefix}java.lang.String, android.content.Intent, int, android.os.Bundle)"
                ))
                rulesFile.set(layout.buildDirectory.file("generated/instrumentation-keeps/${variant.name}.pro"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(generate).toGet(
                ScopedArtifact.CLASSES,
                GenerateInstrumentationKeeps::getTargetJars,
                GenerateInstrumentationKeeps::getTargetDirectories
            )
            test.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(generate).toGet(
                ScopedArtifact.CLASSES,
                GenerateInstrumentationKeeps::getSourceJars,
                GenerateInstrumentationKeeps::getSourceDirectories
            )
            // Provider carries the generation dependency to app R8. Test class
            // compilation needs app classfiles, never app R8/mapping/APK outputs.
            variant.proguardFiles.add(generate.flatMap { it.rulesFile })
        }
    }
}

dependencies {
    val suppliedAar = providers.gradleProperty("tinkleSdkTestAar").orElse(providers.environmentVariable("TINKLE_SDK_TEST_AAR"))
    val publishedCoordinate = providers.gradleProperty("tinkleSdkCoordinate")
    if (suppliedAar.isPresent) {
        implementation(files(suppliedAar.get()))
        // A file AAR has no POM. The default Maven smoke below proves transitivity;
        // this explicit CI adapter proves the exact candidate/published AAR bytes.
        implementation("net.java.dev.jna:jna:5.18.1@aar")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    } else {
        implementation(publishedCoordinate.orElse("com.github.tinklehq:tinkle-sdk-kotlin:${rootProject.version}").get())
    }
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // AndroidX Test monitor references these annotations without declaring them in its POM.
    // CLASS-retained annotations belong on R8's library classpath, not in the test APK.
    androidTestCompileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
