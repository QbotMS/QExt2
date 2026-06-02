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
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = System.getenv("GPR_USER") ?: providers.gradleProperty("gpr.user").getOrElse("")
                password = System.getenv("GPR_TOKEN") ?: providers.gradleProperty("gpr.key").getOrElse("")
            }
        }
    }
}

rootProject.name = "QExt2"
include(":app")
