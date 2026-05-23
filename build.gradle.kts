import java.time.Duration

plugins {
    id("org.jetbrains.intellij.platform") version "2.11.0"
    java
}

group = "org.jfxcore"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6")
        bundledPlugin("org.jetbrains.plugins.javaFX")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.editorconfig.editorconfigjetbrains")
        bundledPlugin("com.intellij.properties")
        pluginVerifier()
        zipSigner()

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    // JUnit4 runtime needed by IntelliJ's JUnit5TestSessionListener (LauncherSessionListener SPI)
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Force the modern annotations jar (TYPE_USE support for @NotNull/@Nullable on array
    // components). The bundled Kotlin plugin ships annotations-13.0 which predates TYPE_USE,
    // causing compile errors on "Object @Nullable []" patterns throughout the codebase.
    compileOnly("org.jetbrains:annotations:26.0.2")
    testCompileOnly("org.jetbrains:annotations:26.0.2")

    compileOnly("org.jetbrains.kotlin:kotlin-compiler-common-for-ide:2.2.21-484") {
        isTransitive = false
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = "org.jfxcore.fxml"
        name = "FXML 2.0"
        version = project.version.toString()
        description = """
            Provides IDE support for FXML 2.0 files used by the JFXcore FXML compiler.
            A file is recognized as FXML 2.0 when the root element carries both
            xmlns="http://javafx.com/javafx" and xmlns:fx="http://jfxcore.org/fxml/2.0".
        """.trimIndent()

        ideaVersion {
            sinceBuild = "252"
        }
    }

    signing {
        certificateChainFile = file("certificate/chain.crt")
        privateKeyFile = file("certificate/private.pem")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// Prevent the patchPluginXml task from being skipped due to Gradle configuration cache,
// which would cause stale extension registrations to be packaged into the jar.
tasks.named("patchPluginXml") {
    outputs.upToDateWhen { false }
}

tasks.test {
    useJUnitPlatform()
    timeout = Duration.ofMinutes(10)
    jvmArgs("-Xshare:off", "-Xmx4g")
}

// Disable CodeWithMe in the sandbox — it produces irrelevant preload warnings at startup.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    disabledPlugins.add("com.jetbrains.codeWithMe")
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // Always rebuild so the sandbox jar never runs stale code.
    dependsOn("buildPlugin")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf(
            "-Didea.log.debug.categories=#org.jfxcore.fxml",
            // Suppress "[warning][cds]": JVM CDS disabled because IntelliJ sets
            // java.system.class.loader. Expected in sandbox, not actionable.
            "-Xshare:off",
            // Suppress BundledSharedIndexProvider WARN "Bundled shared index is not found":
            // sandbox IDE never ships pre-built JDK shared indexes.
            "-Dshared.indexes.bundled=false",
        )
    }
}
