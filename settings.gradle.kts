pluginManagement {
    repositories {
        // NeoGradle（net.neoforged.gradle.userdev）发布在 NeoForged 的 maven 上。
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

// NeoGradle 需要一个 Java 21 工具链来跑 JavaSourceTransformer，
// 模组本身仍然编译到 17；CI 上只有 17，所以让 Gradle 自己下载 21。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "wes"
