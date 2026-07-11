pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
        }
        mavenCentral()
    }
}

rootProject.name = "minecraft-agent"

include("paper-plugin")
include("client-mod")
