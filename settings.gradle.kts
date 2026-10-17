pluginManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.Moriafly.spw-workshop-api") } }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.xuncorp.spw.workshop") {
                useModule("com.github.Moriafly.spw-workshop-api:spw-workshop-gradle-plugin:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*") } }
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.Moriafly.spw-workshop-api") } }
    }
}
rootProject.name = "dynamic-lyrics-island-for-spw"
