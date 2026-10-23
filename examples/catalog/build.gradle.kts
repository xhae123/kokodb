plugins {
    kotlin("jvm")
    application
    id("com.google.devtools.ksp")
}

repositories { mavenCentral() }
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":"))
    ksp(project(":processor"))
    runtimeOnly("com.h2database:h2:2.5.252")
}

application { mainClass.set("catalog.MainKt") }
