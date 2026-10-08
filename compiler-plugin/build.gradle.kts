plugins {
    kotlin("jvm")
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        // Compile the runtime grammar into the plugin without exposing internals or depending on the engine.
        kotlin.srcDir(rootProject.file("src/main/kotlin"))
        kotlin.include(
            "kokodb/compiler/**", "kokodb/sql/**", "kokodb/query/**",
            "kokodb/storage/Table.kt", "kokodb/storage/Value.kt", "kokodb/DatabaseException.kt",
        )
    }
}

dependencies {
    compileOnly(kotlin("compiler-embeddable"))
}

tasks.jar {
    archiveFileName.set("kokodb-sql-compiler-plugin.jar")
}
