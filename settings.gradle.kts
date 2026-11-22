pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView (motor Firefox), usado quando a build inclui o Gecko (padrão; -PsemGecko tira).
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
    }
}

rootProject.name = "HubWeb"
include(":app")
