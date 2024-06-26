import com.github.raymank26.JavalinOpenApiPluginExtension

plugins {
    kotlin("jvm") version "1.9.0"
    id("com.github.raymank26.javalin-openapi")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("io.javalin:javalin:5.6.1")
    implementation("org.slf4j:slf4j-simple:2.0.7")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.create("foo") {
    dependsOn("generateOpenApiClasses")
}

tasks.getByName("generateOpenApiClasses") { // this is needed to run the task for debugging
    outputs.upToDateWhen { false }
}

kotlin {
    jvmToolchain(11)
}

tasks.test {
    useJUnitPlatform()
}

configure<JavalinOpenApiPluginExtension> {
    targets {
        register("sampleTarget") {
            basePackageName.set("foo")
        }
    }
}

sourceSets {
    main {
        kotlin {
            srcDir("$buildDir/generated/main/kotlin")
        }
    }
}