// Build layout adapted from Moriafly/spw-workshop-api (Apache-2.0).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.compose") version "1.12.0"
    `java-library`
}
group = "io.github.gaboron"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
val workshop = "com.github.Moriafly:spw-workshop-api:0.1.0-dev20"
val projectUrl = providers.gradleProperty("projectUrl")
val currentOs = OperatingSystem.current()
val targetPlatform = providers.gradleProperty("targetPlatform").orNull
require(targetPlatform == null || targetPlatform == "windows" || targetPlatform == "linux") {
    "targetPlatform must be windows or linux"
}
val isWindows = targetPlatform?.let { it == "windows" } ?: currentOs.isWindows
val isLinux = targetPlatform?.let { it == "linux" } ?: currentOs.isLinux
val metadataSources by configurations.creating { isTransitive = false }
dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(workshop) { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation(compose.desktop.currentOs)
    metadataSources("net.jthink:jaudiotagger:3.0.1:sources")
}
tasks.processResources {
    from("native/shared-fonts/MiSansVF.ttf") { into("fonts") }
    if (isWindows) {
        dependsOn("buildSpectrum")
        dependsOn("buildWindowsTray")
        from(layout.buildDirectory.file("native/spw-spectrum.exe")) { into("native") }
        from(layout.buildDirectory.file("native/spw-island-tray.exe")) { into("native") }
        from({ zipTree(configurations.runtimeClasspath.get().single {
            it.name.startsWith("skiko-awt-runtime-windows-x64-")
        }) }) {
            into("native/compose")
            include("skiko-windows-x64.dll", "icudtl.dat")
        }
    } else if (isLinux) {
        exclude { it.file == file("src/main/resources/preference_config.json") }
        from("src/linux/resources")
    }
    inputs.property("projectUrl", projectUrl)
    filesMatching("project.properties") { expand("projectUrl" to projectUrl.get()) }
}
tasks.jar {
    manifest.attributes(
        "Plugin-Class" to "io.github.gaboron.spwisland.host.IslandPlugin",
        "Plugin-Id" to "io.github.gaboron.spwisland",
        "Plugin-Name" to "Dynamic Lyrics Island for SPW",
        "Plugin-Version" to project.version.toString(),
        "Plugin-Provider" to "GaBoron",
        "Plugin-Description" to "SPW lyrics island; concept by Lyricify / WXRIW (CC BY-SA 4.0)",
        "Plugin-Has-Config" to "true",
        "Plugin-Open-Source-Url" to projectUrl.get()
    )
}
tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("spw-island-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src") { into("src") }
    from("native") {
        into("native")
        exclude("**/bin/**", "**/obj/**")
    }
    from("gradle") { into("gradle") }
    from("licenses") { into("licenses") }
    from("docs") { into("docs") }
    from("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
        "README.md", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", ".gitignore")
}

tasks.register<Exec>("buildSpectrum") {
    val output = layout.buildDirectory.file("native/spw-spectrum.exe")
    inputs.files(fileTree("native") { include("*.cs") })
    outputs.file(output)
    onlyIf {
        if (!isWindows) logger.lifecycle("Skipping the Windows spectrum helper on ${currentOs.name}")
        isWindows
    }
    doFirst { output.get().asFile.parentFile.mkdirs() }
    executable = "${System.getenv("WINDIR") ?: "C:/Windows"}/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    args("/nologo", "/target:winexe", "/platform:x64", "/optimize+", "/out:${output.get().asFile.absolutePath}",
        file("native/AudioInterop.cs").absolutePath, file("native/Spectrum.cs").absolutePath,
        file("native/ProcessLoopback.cs").absolutePath, file("native/SpectrumLevels.cs").absolutePath)
}

tasks.register<Exec>("buildWindowsTray") {
    val output = layout.buildDirectory.file("native/spw-island-tray.exe")
    inputs.file("native/Tray.cs")
    outputs.file(output)
    onlyIf { isWindows }
    doFirst { output.get().asFile.parentFile.mkdirs() }
    executable = "${System.getenv("WINDIR") ?: "C:/Windows"}/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    args("/nologo", "/target:winexe", "/platform:x64", "/optimize+",
        "/reference:System.Windows.Forms.dll", "/reference:System.Drawing.dll",
        "/out:${output.get().asFile.absolutePath}", file("native/Tray.cs").absolutePath)
}

fun registerPluginArchive(taskName: String, platform: String, enabled: Boolean) = tasks.register<Zip>(taskName) {
    val libraryNames by lazy {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val duplicates = artifacts.groupBy { it.file.name }.filterValues { it.size > 1 }.keys
        artifacts.associate { artifact ->
            artifact.file.canonicalPath to if (artifact.file.name in duplicates) {
                "${artifact.moduleVersion.id.group}-${artifact.file.name}"
            } else artifact.file.name
        }
    }
    dependsOn(tasks.jar, "sourceArchive")
    onlyIf {
        if (!enabled) logger.lifecycle("$taskName must run on a $platform host")
        enabled
    }
    archiveFileName.set("spw-island-${project.version}-$platform-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("classes") { from(tasks.jar.map { zipTree(it.archiveFile) }) }
    into("lib") {
        from(configurations.runtimeClasspath) {
            eachFile {
                if (platform == "linux" &&
                    !file.name.startsWith("jna-") &&
                    !file.name.startsWith("jna-platform-") &&
                    !file.name.startsWith("jaudiotagger-")) {
                    exclude()
                } else {
                    name = libraryNames[file.canonicalPath] ?: name
                }
            }
        }
    }
    into("licenses") { from("licenses") }
    into("source") { from(tasks.named("sourceArchive")); from(metadataSources) }
    from("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "README.md")
}

val pluginWindows = registerPluginArchive("pluginWindows", "windows", isWindows)
val pluginLinux = registerPluginArchive("pluginLinux", "linux", isLinux)

tasks.register("plugin") {
    group = "build"
    description = "Builds the plugin archive for the current host platform."
    dependsOn(if (isWindows) pluginWindows else pluginLinux)
    doFirst {
        check(isWindows || isLinux) { "Only Windows and Linux plugin archives are supported" }
    }
}
