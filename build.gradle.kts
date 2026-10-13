plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
    kotlin("jvm") version "2.1.0"
}

group = "com.example"
version = "1.0.0"

dependencies {
    // Compile against IntelliJ IDEA 2024.3 (build 243), which is cached locally.
    // The plugin only uses long-standing, stable platform APIs that also exist in 2024.1,
    // so the declared compatibility range below (sinceBuild 241) is safe at runtime.
    intellijPlatform {
        intellijIdea("2024.3")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    // The bundled IDE SDK drags JUnit 4 classes onto the test runtime classpath;
    // provide them so the JUnit Platform executor can start.
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        // Vendor info patched into plugin.xml; change to your real identity
        // before publishing to JetBrains Marketplace.
        vendor {
            name = "liuqiyu-cmd"
            email = "liuqiyu@dbhl.cn"
            url = "https://github.com/liuqiyu-cmd/json-transformer-plugin"
        }
        ideaVersion {
            // Compatible with IDEA 2024.1 (build 241) through 2024.3 (build 243.*).
            sinceBuild = "241"
            untilBuild = "243.*"
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
