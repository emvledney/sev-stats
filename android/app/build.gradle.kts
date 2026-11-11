import com.android.build.api.artifact.SingleArtifact

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Play package name, set in gradle.properties. Published on Play, so it can never change.
val silveradoApplicationId: String =
  providers.gradleProperty("silverado.applicationId").getOrElse("dev.silveradostats.app")

android {
  namespace = "dev.silveradostats"
  compileSdk = 36

  // Compiles android.car.* (CarPropertyManager, CarUxRestrictionsManager) against the
  // SDK's optional android.car.jar. At runtime the head unit provides the library;
  // the manifest declares <uses-library android:name="android.car" android:required="false"/>.
  useLibrary("android.car")

  defaultConfig {
    applicationId = silveradoApplicationId
    minSdk = 32 // Android 12L (GM SQBR builds)
    targetSdk = 35 // Play requirement for Android Automotive OS apps
    versionCode = 19
    versionName = "0.15.0"
  }

  buildTypes {
    release {
      // No minification. No signing config: android/scripts/release-bundle.sh signs with the upload key.
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
    aidl = false
    shaders = false
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
  lint {
    // targetSdk 35 is deliberate (Play requirement for AAOS); compileSdk is 36.
    disable += "OldTargetApi"
    abortOnError = true
    checkDependencies = false
  }
}

// SourceSafetyTest reads the source sets and manifests under src/ straight from disk. Declare them
// as test inputs so that any source or manifest change re-runs it: with build caching on, the
// test would otherwise stay UP-TO-DATE / FROM-CACHE after a manifest-only edit.
val scannedSources = layout.projectDirectory.dir("src")
tasks.withType<Test>().configureEach {
  inputs.dir(scannedSources).withPropertyName("scannedSources").withPathSensitivity(PathSensitivity.RELATIVE)
}

/** Hands the variant's merged manifest (app + every library) to SourceSafetyTest as a tracked input. */
class MergedManifestArgument(
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val manifest: Provider<RegularFile>,
) : CommandLineArgumentProvider {
  override fun asArguments(): List<String> = listOf("-Dsilverado.mergedManifest=${manifest.get().asFile.absolutePath}")
}

// SourceSafetyTest also checks what the libraries add to the APK's manifest (permissions,
// exported components, the distraction-optimized tag), not only src/main/AndroidManifest.xml.
androidComponents {
  onVariants { variant ->
    val merged = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val testTask = "test${variant.name.replaceFirstChar { it.uppercase() }}UnitTest"
    tasks.withType<Test>().matching { it.name == testTask }.configureEach {
      jvmArgumentProviders += MergedManifestArgument(merged)
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
