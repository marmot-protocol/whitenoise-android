plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm-tree:9.9.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}
