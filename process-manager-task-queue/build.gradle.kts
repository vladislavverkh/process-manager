import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    id("io.spring.dependency-management")
    id("maven-publish")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.3")
    }
}

dependencies {
    api(project(":process-manager-core"))

    implementation("dev.verkhovskiy:task-queue-core:0.0.1-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.mockito:mockito-junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
    publications {
        create("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifactId = "process-manager-task-queue"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
