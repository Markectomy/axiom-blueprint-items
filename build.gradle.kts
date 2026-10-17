import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom") version "1.18.4"
    kotlin("jvm") version "2.4.21"
    `maven-publish`
}

version = property("mod_version")!!
group = property("maven_group")!!

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases/") { content { includeGroup("com.terraformersmc") } }
    maven("https://api.modrinth.com/maven")
}

val axiomDistribution = configurations.create("axiomDistribution") { isTransitive = false }
val extractAxiomClientApi = tasks.register<Copy>("extractAxiomClientApi") {
    from(provider { zipTree(axiomDistribution.singleFile) }) {
        include("META-INF/jars/axiomclientapi-unobf.jar")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("axiom-api"))
}

loom {
    splitEnvironmentSourceSets()
    mods {
        register("axiom_blueprint_items") {
            sourceSet(sourceSets.main.get())
            sourceSet(sourceSets["client"])
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "axiom_blueprint_items_test"
        enableGameTests = false
        enableClientGameTests = true
    }
}

sourceSets["gametest"].compileClasspath += sourceSets["client"].output
sourceSets["gametest"].runtimeClasspath += sourceSets["client"].output

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    implementation("net.fabricmc:fabric-language-kotlin:1.14.2+kotlin.2.4.21")
    // Axiom shades an old SLF4J API. Put Minecraft's API first so Fabric's
    // development library classifier does not mistake Axiom for a logging library.
    "clientImplementation"("org.slf4j:slf4j-api:2.0.17")
    "clientImplementation"("maven.modrinth:axiom:Sc1qsNWg")
    "clientCompileOnly"("com.terraformersmc:modmenu:20.0.3")
    if (providers.gradleProperty("withModMenu").orElse("true").get().toBoolean()) {
        "clientRuntimeOnly"("com.terraformersmc:modmenu:20.0.3")
    }
    add(axiomDistribution.name, "maven.modrinth:axiom:Sc1qsNWg")
    "clientCompileOnly"(files(layout.buildDirectory.file("axiom-api/axiomclientapi-unobf.jar")).builtBy(extractAxiomClientApi))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
val resourceVersion = version.toString()
tasks.processResources {
    inputs.property("version", resourceVersion)
    filesMatching("fabric.mod.json") { expand("version" to resourceVersion) }
}
tasks.withType<Jar>().configureEach {
    from("LICENSE") { into("META-INF") }
}
tasks.test { useJUnitPlatform() }
