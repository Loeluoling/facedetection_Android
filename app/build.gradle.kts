plugins {
    alias(libs.plugins.android.application)
    id("com.huawei.agconnect")
}

android {
    namespace = "com.example.facedemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.facedemo"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation(libs.room.runtime.android)
    implementation(libs.room.common.jvm)
    // CameraX 核心库 - 统一版本
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    implementation ("com.huawei.security:localauthentication:1.1.1")

    // 添加完整的华为ML Kit人脸验证相关依赖
    implementation("com.huawei.hms:ml-computer-vision-faceverify:3.11.0.302")
    implementation("com.huawei.hms:ml-computer-vision-faceverify-model:3.11.0.302")

    // 使用AndroidX的multidex替代旧版
    implementation("androidx.multidex:multidex:2.0.1")

    // 更新AndroidX支持库版本
    implementation("androidx.appcompat:appcompat:1.6.1")

    // 其他依赖
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")

    //room依赖
    implementation ("androidx.room:room-runtime:2.5.2")
    annotationProcessor ("androidx.room:room-compiler:2.5.2") // Java 项目使用 annotationProcessor

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}