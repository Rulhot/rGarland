plugins {
    id("java-library")
    id("io.freefair.lombok") version "9.0.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        content {
            includeGroupAndSubgroups("io.papermc")
            includeGroup("net.md-5")
            includeGroup("com.mojang")
        }
    }
    maven("https://repo.codemc.io/repository/maven-releases/") {
        content { includeGroup("com.github.retrooper") }
    }
    maven("https://maven.enginehub.org/repo/") {
        content { includeGroupByRegex("com\\.sk89q.*") }
    }
    maven("https://jitpack.io") {
        content {
            includeGroup("com.github.GriefPrevention")
            includeGroup("com.github.CakesStudio")
        }
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9") { isTransitive = false }
    compileOnly("com.sk89q.worldguard:worldguard-core:7.0.9") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.0") { isTransitive = false }
    compileOnly("com.github.GriefPrevention:GriefPrevention:16.18.4") { isTransitive = false }
    compileOnly("com.github.CakesStudio:CakeRegionsAPI:v2.0.3") { isTransitive = false }
}

lombok {
    version = "1.18.42"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal"))
    }
    processResources {
        val properties = mapOf("version" to project.version)
        inputs.properties(properties)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(properties)
        }
    }
}
