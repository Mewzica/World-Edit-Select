plugins {
    id("fabric-loom") version "1.10.5"
}

val modVersion = providers.gradleProperty("mod_version").get()
val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val yarnMappings = providers.gradleProperty("yarn_mappings").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val worldeditVersion = providers.gradleProperty("worldedit_version").get()

group = "dev.wes"
version = "$modVersion+mc$minecraftVersion"

base {
    archivesName.set("wes")
}

repositories {
    // WorldEdit 的 Maven 仓库（worldedit-core 就在这里）
    maven("https://maven.enginehub.org/repo/") { name = "EngineHub" }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")

    // 只需要 WorldEdit 的「核心 API」（BlockType / BlockFactory / Registry 等）。
    // 这些类与 Minecraft 版本无关，所以直接拿 worldedit-core 编译即可；
    // 运行期真正的实现由玩家安装的 WorldEdit 模组提供。
    compileOnly("com.sk89q.worldedit:worldedit-core:$worldeditVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version.toString()))
    }
}
