cmake_minimum_required(VERSION 3.22.1)
project("facedemo")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
find_library(log-lib log)
if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    add_compile_options(-std=c++17)
endif()
# 包含当前目录和ncnn头文件目录
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}           # 当前cpp目录，包含face_detector.h
        ${CMAKE_CURRENT_SOURCE_DIR}/include   # ncnn头文件目录
)

add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so
)

add_library(native-lib SHARED
        native-lib.cpp
        face_detector.cpp
)

target_link_libraries(native-lib
        ncnn
        android
        jnigraphics
        ${log-lib}
)