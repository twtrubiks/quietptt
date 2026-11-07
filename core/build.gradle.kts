import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 純 Kotlin，沒有 Android：連線（SSH、WebSocket）、終端模擬、Big5-UAO、畫面辨識、操作流程。
// 這裡的程式在 JVM 單元測試裡幾秒就能跑完；tools/capture 也直接用這裡的連線與終端模擬
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsch)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver3)
}
