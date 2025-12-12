pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // 配置HMS Core SDK的Maven仓地址（插件）
        maven { url = uri("https://developer.huawei.com/repo/")

        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 配置HMS Core SDK的Maven仓地址（依赖库）
        maven { url = uri("https://developer.huawei.com/repo/")

        }
    }
}

rootProject.name = "facedemo"
include(":app")