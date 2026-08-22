plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-project"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
