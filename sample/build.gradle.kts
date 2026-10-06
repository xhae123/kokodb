plugins {
    kotlin("jvm")
    application
    id("com.google.devtools.ksp")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":"))
    ksp(project(":processor"))
}

application {
    mainClass = "sample.MainKt"
}
