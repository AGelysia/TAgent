import org.gradle.api.tasks.PathSensitivity

plugins {
    java
    alias(libs.plugins.spotless)
}

base {
    archivesName = "minecraft-agent-paper"
}

val pluginVersion = version.toString()
val protocolDirectory = rootProject.layout.projectDirectory.dir("protocol")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.gson)
    compileOnly(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.gson)
    testImplementation(libs.java.websocket)
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito)
    testImplementation(libs.paper.api)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    withSourcesJar()
}

sourceSets.test {
    java.srcDir(rootProject.file("protocol/jvm-test/src/test/java"))
}

spotless {
    java {
        googleJavaFormat(
            libs.versions.google.java.format
                .get(),
        )
        target("src/**/*.java")
    }
    kotlinGradle {
        ktlint()
        target("*.gradle.kts")
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.property("version", pluginVersion)
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
    from(protocolDirectory.dir("schemas")) {
        into("protocol/schemas")
    }
}

tasks.test {
    useJUnitPlatform()
    maxParallelForks = 1
    inputs.dir(protocolDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(protocolDirectory.dir("fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("minecraftAgent.protocolDir", protocolDirectory.asFile.absolutePath)
}

tasks.jar {
    manifest.attributes["Implementation-Version"] = pluginVersion
}
