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

val kokoSqlCompilerPlugin by configurations.creating {
    isTransitive = false
}

dependencies {
    kokoSqlCompilerPlugin(project(":compiler-plugin"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.files(kokoSqlCompilerPlugin).withPropertyName("kokoSqlCompilerPlugin")
        .withPathSensitivity(PathSensitivity.NONE)
    compilerOptions.freeCompilerArgs.add(kokoSqlCompilerPlugin.elements.map { files ->
        "-Xplugin=${files.single().asFile.absolutePath}"
    })
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    // KSP reads compiler arguments before compilation, so the plugin artifact must already exist.
    dependsOn(kokoSqlCompilerPlugin)
}

application {
    mainClass = "sample.MainKt"
}

tasks.register<JavaExec>("runPersistent") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("sample.PersistentMainKt")
}
