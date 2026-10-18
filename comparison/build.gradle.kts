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
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    runtimeOnly("com.h2database:h2:2.5.252")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

application { mainClass.set("org.openjdk.jmh.Main") }

tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("comparison.Smoke")
}

tasks.register<JavaExec>("differential") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("comparison.DifferentialCheck")
    jvmArgs("-Xms64m", "-Xmx256m", "-XX:-UsePerfData")
}

tasks.register<JavaExec>("coroutineStudy") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("comparison.CoroutineContractStudy")
    jvmArgs("-Xms64m", "-Xmx256m", "-XX:-UsePerfData")
}
