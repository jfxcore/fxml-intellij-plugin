import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.time.Duration

plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    java
}

group = "org.jfxcore"
version = project.findProperty("TAG_VERSION") ?: "1.0-SNAPSHOT"

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

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
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
        name = "FXML/2 for JavaFX"
        version = project.version.toString()
        description = """
            IDE support for the <a href="https://jfxcore.github.io/fxml-compiler">FXML/2</a>
            markup format for JavaFX UIs.<br/><br/>

            Features include:
            <ul>
                <li>FXML syntax highlighting, folding, formatting, and EditorConfig-aware indentation
                <li>Tag and attribute resolution, code completion, and navigation to JavaFX classes
                <li>Rename, find usages, and go to declaration for <code>fx:id</code> and bindings
                <li>Inspections for unresolved tags and attributes, unused imports, invalid values, and more
                <li>Import optimization and intentions to move markup between <code>.fxml</code>
                    files and embedded markup with <code>@ComponentView</code>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "252"
        }
    }

    signing {
        certificateChain = providers.gradleProperty("certificateChain")
        privateKey = providers.gradleProperty("signingKey")
        password = providers.gradleProperty("signingPassword")
    }

    publishing {
        token = providers.gradleProperty("publishingToken")
    }

    pluginVerification {
        ides {
            recommended()
        }
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

// Disable CodeWithMe in the sandbox, it produces irrelevant preload warnings at startup.
tasks.named<PrepareSandboxTask>("prepareSandbox") {
    disabledPlugins.add("com.jetbrains.codeWithMe")
}

tasks.named<RunIdeTask>("runIde") {
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
