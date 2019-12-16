plugins {
    kotlin("jvm")
    java
    id("org.jetbrains.dokka") version "0.10.0"
}

dependencies {
    val jupiterVersion = "5.5.2"
    implementation(kotlin("stdlib"))
    implementation("org.javassist:javassist:3.26.0-GA")
    implementation("org.apache.bcel:bcel:6.4.1")
    implementation("org.junit.jupiter:junit-jupiter:$jupiterVersion")
    implementation("org.objenesis:objenesis:3.1")
    implementation("com.google.code.gson:gson:2.8.6")

    testImplementation("org.junit.jupiter:junit-jupiter:$jupiterVersion")
}

tasks.dokka {
    outputFormat = "html"
    outputDirectory = "$buildDir/javadoc"
}
tasks.test {
    useJUnitPlatform()
}