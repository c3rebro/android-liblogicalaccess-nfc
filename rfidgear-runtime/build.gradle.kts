plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-project"))
    implementation(project(":core-card"))
    implementation(project(":core-execution"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}
