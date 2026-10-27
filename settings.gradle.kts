pluginManagement {
    repositories {
        // ForgeGradle 6 不在 Gradle Plugin Portal 上，必须指向 Forge 的 maven。
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        // MixinGradle（org.spongepowered.mixin）在 Sponge 的仓库里。
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "wes"
