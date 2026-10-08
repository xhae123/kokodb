plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation(project(":"))
}

application {
    mainClass = "benchmark.Benchmark"
}

tasks.register<JavaExec>("storageSmoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("benchmark.DurabilityBenchmark")
    val directory = layout.buildDirectory.dir("storage-smoke")
    doFirst {
        directory.get().asFile.mkdirs()
        args(directory.get().asFile.resolve("run-${System.nanoTime()}").apply { mkdirs() }.absolutePath, "smoke")
    }
}
