pluginManagement {
    repositories {
        // ModDevGradle（net.neoforged.moddev）发布在 NeoForged 的 maven 上。
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "wes"
