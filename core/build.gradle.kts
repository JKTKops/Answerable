plugins {
    kotlin("jvm")
    java
    id("org.jetbrains.dokka") version "0.9.18"
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.javassist:javassist:3.25.0-GA")
    implementation("org.apache.bcel:bcel:6.0")
    implementation("org.junit.jupiter:junit-jupiter:5.4.2")
    implementation("org.objenesis:objenesis:3.0.1")
    implementation("com.google.code.gson:gson:2.8.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.4.2")
}

tasks.dokka {
    outputFormat = "html"
    outputDirectory = "$buildDir/javadoc"
}
tasks.test {
    useJUnitPlatform()
}