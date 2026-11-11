import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 連真的 PTT，把畫面存成測試用的 fixture。雲端 VM 用 --ws（SSH 的 22 埠被擋），自己的電腦兩種都可以。
// 跑法：./gradlew :tools:capture:run -q --console=plain
// 用的是 core 裡同一套 SshTransport 與 Terminal，所以存下來的畫面跟 App 看到的一樣
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    mainClass = "com.twtrubiks.quietptt.capture.MainKt"
}

tasks.named<JavaExec>("run") {
    // 從鍵盤讀指令
    standardInput = System.`in`
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // --ws 時用 WebSocket 連線
    implementation(libs.okhttp)
}
