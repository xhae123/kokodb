plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
    id("com.google.devtools.ksp") version "2.3.10"
}

group = "io.github.xhae123"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(kotlin("compiler-embeddable"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    kspTest(project(":processor"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kokodb.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
}
