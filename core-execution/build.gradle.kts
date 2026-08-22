plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core-project"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
