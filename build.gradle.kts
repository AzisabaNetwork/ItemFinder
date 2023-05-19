plugins {
    kotlin("jvm") version "1.6.10"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "net.azisaba"
version = "1.1.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://repo.acrylicstyle.xyz/repository/maven-public/") }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.6.10")
    implementation("xyz.acrylicstyle.util:kotlin:0.16.6")
    compileOnly("org.spigotmc:spigot:1.15.2-R0.1-SNAPSHOT")
}

tasks {
    compileKotlin { kotlinOptions.jvmTarget = "1.8" }

    shadowJar {
        relocate("kotlin", "net.azisaba.itemFinder.libs.kotlin")
        relocate("util", "net.azisaba.itemFinder.libs.util")

        minimize()
    }

    withType<org.gradle.jvm.tasks.Jar> {
        archiveFileName.set("ItemFinder-${archiveVersion.get()}.jar")
    }
}
