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
