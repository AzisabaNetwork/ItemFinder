plugins {
    kotlin("jvm") version "1.5.30"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "net.azisaba"
version = "1.0.3"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://repo2.acrylicstyle.xyz") }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.5.30")
    implementation("xyz.acrylicstyle:java-util-kotlin:0.15.4")
    compileOnly("org.spigotmc:spigot:1.15.2-R0.1-SNAPSHOT")
}

tasks {
    compileKotlin { kotlinOptions.jvmTarget = "1.8" }

    shadowJar {
        relocate("kotlin", "net.azisaba.itemFinder.libs.kotlin")
        relocate("util", "net.azisaba.itemFinder.libs.util")
        relocate("net.blueberrymc.native_util", "net.azisaba.itemFinder.libs.net.blueberrymc.native_util")

        minimize()
    }

    withType<org.gradle.jvm.tasks.Jar> {
        archiveFileName.set("ItemFinder-${archiveVersion.get()}.jar")
    }
}
