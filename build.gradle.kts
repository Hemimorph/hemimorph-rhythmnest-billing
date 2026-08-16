plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "io.github.hemimogph"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
    sourceSets {
        main {
            kotlin.srcDir("src")
        }
        test {
            kotlin.srcDir("test")
        }
    }
}

sourceSets {
    main {
        resources.srcDir("resources")
    }
}

val openApiSourceSet = sourceSets.create("openApi") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

kotlin.sourceSets.named("openApi") {
    kotlin.srcDir("openapi")
}

configurations[openApiSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())
configurations[openApiSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

application {
    applicationName = "rhythmnest-billing"
    mainClass = "io.github.hemimogph.MainKt"
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:3.4.3"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-server-double-receive")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-routing-openapi")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation(libs.logback.classic)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(libs.h2)
    add(openApiSourceSet.implementationConfigurationName, "io.ktor:ktor-server-test-host")
}

tasks.test {
    useJUnitPlatform()
}

val openApiOutput = layout.buildDirectory.file("openapi/openapi.json")

val generateOpenApi by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Generates the OpenAPI JSON document"
    dependsOn(openApiSourceSet.classesTaskName)
    classpath = openApiSourceSet.runtimeClasspath
    mainClass = "io.github.hemimogph.OpenApiGeneratorKt"
    doFirst {
        args(openApiOutput.get().asFile.absolutePath)
    }
    outputs.file(openApiOutput)
}

tasks.build {
    dependsOn(generateOpenApi)
}
