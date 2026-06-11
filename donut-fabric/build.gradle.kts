plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("maven-publish")
    kotlin("jvm") version "1.9.22" apply false
}

version = "1.0.0"
group = "com.n8tech"

base {
    archivesName.set("donut-fabric")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

val minecraftVersion = "1.21.1"
val yarnMappings = "1.21.1+build.3"
val loaderVersion = "0.16.9"
val fabricVersion = "0.102.0+1.21.1"

dependencies {
    // Fabric core
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")

    // SQLite - bundled, no external DB required by default
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    include("org.xerial:sqlite-jdbc:3.45.3.0")

    // MongoDB driver (optional; activated via config)
    implementation("org.mongodb:mongodb-driver-sync:5.1.0")
    include("org.mongodb:mongodb-driver-sync:5.1.0")

    // YAML configuration
    implementation("org.yaml:snakeyaml:2.2")
    include("org.yaml:snakeyaml:2.2")

    // Guava (already in MC, but explicit for clarity)
    implementation("com.google.guava:guava:33.2.1-jre")

    // Caffeine caching (async-safe, high-performance)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    include("com.github.ben-manes.caffeine:caffeine:3.1.8")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from("LICENSE") { rename { "${it}_${base.archivesName.get()}" } }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri("${project.projectDir}/repo")
        }
    }
}
