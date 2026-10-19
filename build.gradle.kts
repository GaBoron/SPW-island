// Build layout adapted from Moriafly/spw-workshop-api (Apache-2.0).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.internal.os.OperatingSystem
import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
    `java-library`
}
group = "io.github.gaboron"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { compilerOptions {
    jvmTarget.set(JvmTarget.JVM_25)
    freeCompilerArgs.add("-jvm-default=no-compatibility")
} }
val workshop = "com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23"
val projectUrl = providers.gradleProperty("projectUrl")
val currentOs = OperatingSystem.current()
val metadataSources = configurations.create("metadataSources") { isTransitive = false }
val skikoVersion = "0.150.1"
dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(workshop) { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("net.java.dev.jna:jna:5.17.0")
    compileOnly("net.java.dev.jna:jna-platform:5.17.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.compose.desktop:desktop-jvm:1.12.1")
    implementation("org.jetbrains.skiko:skiko-awt:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:$skikoVersion")
    metadataSources("net.jthink:jaudiotagger:3.0.1:sources")
}
configurations.named("runtimeClasspath") {
    // SPW owns JNA and its native dispatcher; do not leave duplicate JARs in the update directory.
    exclude(group = "net.java.dev.jna")
}
val gnomePointer = tasks.register<Zip>("gnomePointer") {
    archiveFileName.set("spw-island-pointer@gaboron.github.io.shell-extension.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/linux/resources/native/gnome-pointer")
    from("LICENSE")
}
tasks.processResources {
    doFirst {
        check(currentOs.isWindows) { "Build the universal plugin on Windows with the native toolchains installed" }
    }
    from("native/shared-fonts/MiSansVF.ttf") { into("fonts") }
    dependsOn("buildSpectrum", "buildWindowsTray", "buildWindowsGlass", gnomePointer)
    from(layout.buildDirectory.file("native/spw-spectrum.exe")) { into("native") }
    from(layout.buildDirectory.file("native/spw-island-tray.exe")) { into("native") }
    from(layout.buildDirectory.file("native/spw-island-glass.dll")) { into("native") }
    from({ zipTree(configurations.runtimeClasspath.get().single {
        it.name.startsWith("skiko-awt-runtime-windows-x64-")
    }) }) {
        into("native/compose")
        include("skiko-windows-x64.dll", "icudtl.dat")
    }
    from("src/linux/resources") { exclude("**/__pycache__/**", "**/*.pyc", "native/gnome-pointer/**") }
    from(gnomePointer) { into("native") }
    inputs.property("projectUrl", projectUrl)
    filesMatching("project.properties") { expand("projectUrl" to projectUrl.get()) }
}
spmod {
    PluginClass = "io.github.gaboron.spwisland.host.IslandPlugin"
    PluginId = "io.github.gaboron.spwisland"
    PluginName = "灵动词岛 for SPW"
    PluginVersion = project.version.toString()
    PluginProvider = "GaBoron"
    PluginDescription = "SPW 灵动词岛插件：逐字歌词、桌面悬浮与播放控制"
    PluginHasConfig = true
    PluginOpenSourceUrl = projectUrl.get()
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ, PluginPermission.KEY_BINDINGS)
}
tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("spw-island-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src") { into("src"); exclude("test/**", "**/__pycache__/**", "**/*.pyc") }
    from("native") {
        into("native")
        exclude("**/bin/**", "**/obj/**")
    }
    from("gradle") { into("gradle") }
    from("tools/build-glass.ps1") { into("tools") }
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
        currentOs.isWindows
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
    onlyIf { currentOs.isWindows }
    doFirst { output.get().asFile.parentFile.mkdirs() }
    executable = "${System.getenv("WINDIR") ?: "C:/Windows"}/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    args("/nologo", "/target:winexe", "/platform:x64", "/optimize+",
        "/reference:System.Windows.Forms.dll", "/reference:System.Drawing.dll",
        "/out:${output.get().asFile.absolutePath}", file("native/Tray.cs").absolutePath)
}

tasks.register<Exec>("buildWindowsGlass") {
    val output = layout.buildDirectory.file("native/spw-island-glass.dll")
    inputs.file("native/glass/DesktopCapture.cpp")
    inputs.file("native/glass/DesktopReadback.h")
    inputs.file("native/glass/DesktopViewport.h")
    inputs.file("native/glass/DesktopSharedTexture.h")
    inputs.file("tools/build-glass.ps1")
    outputs.file(output)
    onlyIf { currentOs.isWindows }
    executable = "powershell.exe"
    args("-NoProfile", "-File", file("tools/build-glass.ps1").absolutePath,
        "-Output", output.get().asFile.absolutePath)
}

tasks.named<Zip>("plugin") {
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
    archiveFileName.set("spw-island-${project.version}.spmod")
    eachFile {
        if (path.startsWith("lib/")) {
            name = libraryNames[file.canonicalPath] ?: name
        }
    }
    into("licenses") { from("licenses") }
    into("source") { from(tasks.named("sourceArchive")); from(metadataSources) }
    from("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "README.md")
}
