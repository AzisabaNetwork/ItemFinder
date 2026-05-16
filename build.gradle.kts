plugins {
    kotlin("jvm") version "2.3.21"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "net.azisaba"
version = "2.1.1"

val jvmVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(jvmVersion))
}

kotlin {
    jvmToolchain(jvmVersion)
}

paperweight.reobfArtifactConfiguration.set(io.papermc.paperweight.userdev.ReobfArtifactConfiguration.REOBF_PRODUCTION)

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven { url = uri("https://repo.azisaba.net/repository/maven-public/") }
    maven { url = uri("https://repo.acrylicstyle.xyz/repository/maven-public/") }
    maven { url = uri("https://maven.enginehub.org/repo/") }
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    implementation("xyz.acrylicstyle.util:kotlin:0.16.6")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.1.0") {
        exclude("org.bukkit", "bukkit")
    }
    compileOnly("xyz.acrylicstyle:StorageBox:1.6.3+1.21.11")
}

tasks {
    shadowJar {
        enableAutoRelocation = true
        relocationPrefix = "net.azisaba.itemFinder.libs"
    }

    withType<org.gradle.jvm.tasks.Jar> {
        archiveFileName.set("ItemFinder-${archiveVersion.get()}.jar")
    }

    processResources {
        from(
            sourceSets.main
                .get()
                .resources.srcDirs,
        ) {
            include("**")
            val tokenReplacementMap =
                mapOf(
                    "version" to project.version,
                )
            filter<org.apache.tools.ant.filters.ReplaceTokens>("tokens" to tokenReplacementMap)
        }
        filteringCharset = "UTF-8"
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(projectDir) { include("LICENSE") }
    }

    compileJava {
        options.encoding = "UTF-8"
    }
}
