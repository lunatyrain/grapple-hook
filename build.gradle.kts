plugins {
    java
}

group = "dev.grapplehook"
version = "1.0.0"

repositories {
    maven(url = "https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
}

dependencies {
    // if this resolves to a newer build than you run, pin it, e.g. "26.2.build.124-stable"
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
