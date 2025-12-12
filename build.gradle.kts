// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {

    dependencies {
        // 1. 添加Android Gradle插件
        classpath("com.android.tools.build:gradle:8.1.0") // 请使用与您项目兼容的版本，例如8.1.0, 7.3.0等

        // 2. 添加AGC插件
        classpath("com.huawei.agconnect:agcp:1.9.4.300")

        // NOTE: 不要在此处添加应用依赖；它们属于单个模块的build.gradle文件
    }
}


tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

plugins {
    alias(libs.plugins.android.application) apply false
}