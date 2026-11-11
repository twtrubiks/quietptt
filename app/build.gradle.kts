import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.twtrubiks.quietptt"
    // AndroidX（core 1.19、Compose 1.12、lifecycle 2.11）與 okhttp-android 5.5 都要求 compileSdk 37；targetSdk 維持 36（執行時的行為不變）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.twtrubiks.quietptt"
        // Android 9；java.time 從 API 26 就內建，不需要 core library desugaring
        minSdk = 28
        targetSdk = 36
        // 發版時由 release.yml 帶進來：versionCode 是 2026 年起算的分鐘數，只會越來越大，新版才裝得上去
        versionCode = providers.environmentVariable("QUIETPTT_VERSION_CODE").map(String::toInt).getOrElse(1)
        versionName = providers.environmentVariable("QUIETPTT_VERSION_NAME").getOrElse("0.1")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 給別人下載的 APK（GitHub Release）一律用同一把金鑰簽：換了金鑰，手機上的舊版就不能直接更新，
    // 只能移除重裝。金鑰放在 GitHub Secrets，由 release.yml 解開後用環境變數帶進來
    val releaseKeystore = providers.environmentVariable("QUIETPTT_KEYSTORE")
    if (releaseKeystore.isPresent) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore.get())
            storeType = "pkcs12"
            storePassword = providers.environmentVariable("QUIETPTT_KEYSTORE_PASSWORD").get()
            keyAlias = "quietptt"
            keyPassword = providers.environmentVariable("QUIETPTT_KEYSTORE_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            // R8 刪掉沒用到的程式碼並最佳化，Compose 在 release 版會比 debug 版順很多
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 沒有發版金鑰（自己 build、錄影的 CI）就用這台機器的 debug 金鑰簽，能直接安裝，也能覆蓋同一台機器打包的 debug 版
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<Test>().configureEach {
    // CI 只留得下 gradle-build.txt，測試失敗要直接印出期望值與實際值
    testLogging { exceptionFormat = TestExceptionFormat.FULL }
}

// 乾淨守門：release 的依賴樹裡出現這些 group 就不給 build。
// 遙測、廣告、推播、自家後端這些都不會出現在這個 App 裡；新加依賴前先看它會不會把這些拖進來（`./gradlew :app:dependencies`）
val forbiddenGroups = listOf(
    "com.google.firebase",
    "com.google.android.gms",
    "com.google.android.play",
    "com.google.android.ump",
    "com.google.ads",
    "com.facebook",
    "com.appsflyer",
    "com.adjust",
    "io.sentry",
    "com.aotter",
    "com.vpon",
)

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val root = variant.runtimeConfiguration.incoming.resolutionResult.rootComponent
        // 複製一份給 task action 用：直接拿 build script 的 val 會把整個 script 抓進 configuration cache
        val forbidden = forbiddenGroups.toList()
        val check = tasks.register("checkCleanDependencies") {
            group = "verification"
            description = "release 的依賴樹裡不能有 Firebase、Google Play services、廣告或遙測 SDK"
            doLast {
                val seen = HashSet<String>()
                val bad = sortedSetOf<String>()
                fun walk(component: ResolvedComponentResult) {
                    if (!seen.add(component.id.displayName)) return
                    for (dep in component.dependencies) {
                        if (dep !is ResolvedDependencyResult) continue
                        val id = dep.selected.id.displayName
                        if (forbidden.any { id.startsWith(it) }) bad += id
                        walk(dep.selected)
                    }
                }
                walk(root.get())
                if (bad.isNotEmpty()) {
                    throw GradleException("這些依賴不該出現在 QuietPTT 裡（見 app/build.gradle.kts 的 forbiddenGroups）：\n" + bad.joinToString("\n"))
                }
            }
        }
        tasks.named("preBuild") { dependsOn(check) }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.core)
    // WebSocket 連線的 OkHttpClient 由 App 建（整個 App 一個）
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
