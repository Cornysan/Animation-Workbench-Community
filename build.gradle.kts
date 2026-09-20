plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "2.2.21"
}

group = "com.playmation"
version = "0.1.0-SNAPSHOT"
description = "Animation Workbench community portal backend"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

//  Der Build soll sagen koennen, welcher er ist. Ohne das steht man nach jedem
//  Ausrollen vor der Frage, ob die Seite den neuen Stand zeigt oder einen
//  zwischengespeicherten alten - und beantwortet sie, indem man Dateien greppt.
//
//  Die Nummer kommt aus der CI (GITHUB_RUN_NUMBER, zaehlt je Lauf hoch), der
//  Commit aus github.sha. Lokal steht "dev" drin; das ist die ehrliche Antwort,
//  denn ein Stand von der eigenen Platte hat keine Nummer.
springBoot {
    buildInfo {
        properties {
            additional.put("number", providers.gradleProperty("buildNumber").getOrElse("dev"))
            additional.put("commit", providers.gradleProperty("buildCommit").getOrElse("dev").take(7))
        }
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    // Der Seitenrahmen (Marke, Navigation, Konto, Fuss) kommt aus EINER Vorlage
    // und steht fertig im ausgelieferten HTML. Vorher baute ihn JavaScript bei
    // jedem Seitenaufruf neu, und der Kontoblock wartete dabei auf zwei
    // API-Aufrufe - jede Navigation sah deshalb aus wie ein Neuaufbau.
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Die API-Beschreibung entsteht aus dem Code (/v3/api-docs) und kann
    // deshalb nicht von ihm abweichen.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
