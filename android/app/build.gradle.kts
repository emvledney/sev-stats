import com.android.build.api.artifact.SingleArtifact

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// Play package name. Set `silverado.applicationId` in gradle.properties to the
// permanent package name before the first Play Console upload.
val silveradoApplicationId: String =
  providers.gradleProperty("silverado.applicationId").getOrElse("dev.silveradostats.app")

// Map tab web-service keys: MAPS_API_KEY (Google) and OCM_API_KEY (Open Charge Map), kept in the
// untracked android/local.properties. Without them the app still builds; the Map tab stays empty.
val localProperties: Map<String, String> =
  providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull.orEmpty()
    .lines().filter { "=" in it && !it.trimStart().startsWith("#") }
    .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
fun localKey(name: String): String = localProperties[name].orEmpty()

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
    versionCode = 8
    versionName = "0.5.0"
    manifestPlaceholders["MAPS_API_KEY"] = localKey("MAPS_API_KEY")
    buildConfigField("String", "MAPS_API_KEY", "\"${localKey("MAPS_API_KEY")}\"")
    buildConfigField("String", "OCM_API_KEY", "\"${localKey("OCM_API_KEY")}\"")
  }

  buildTypes {
    release {
      // No minification yet; no signing config yet (Play App Signing + upload key later).
      isMinifyEnabled = false
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true // required by the Navigation SDK (Map tab)
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
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  // Templated glance screen (Car App Library): CarAppService + CarAppActivity on Android Automotive.
  implementation(libs.androidx.car.app)
  implementation(libs.androidx.car.app.automotive)
  implementation(libs.androidx.fragment) // = app-automotive's own runtime dependency; see libs.versions.toml
  // Map tab: the bundled Google map view (see libs.versions.toml for why it is not the Maps SDK).
  implementation(libs.google.navigation)
  coreLibraryDesugaring(libs.desugar.jdk.libs)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
