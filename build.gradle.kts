plugins {
    kotlin("jvm") version "2.0.21"
    application
}


// C:/dev is Google Drive-synced and Drive locks files inside build/ mid-task; build.ps1 redirects
// the output directory off the synced tree. Absent the property nothing changes.
providers.gradleProperty("buildOutDir").orNull?.let { layout.buildDirectory.set(file(it)) }

group = "com.brhoom98x.teleroute"
version = "1.0"

kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.brhoom98x.teleroute.server.MainKt")
}

/**
 * One self-contained jar to copy to the server, so deployment is a file and a JRE rather than a
 * dependency tree. Built with `gradle fatJar`; lands in build/libs/teleroute-proxy-<version>-all.jar.
 */
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.brhoom98x.teleroute.server.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
