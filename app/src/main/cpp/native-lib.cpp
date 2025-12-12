#include <jni.h>
#include <android/bitmap.h>
#include <vector>
#include "face_detector.h"
#include <string>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include "face_detector.h"
#include <android/log.h>
#include <ncnn/cpu.h>
#include <omp.h>
#include <mutex>
static std::mutex g_detect_mutex;
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    // 尽早限制 OpenMP 线程（对整个进程生效）
    omp_set_num_threads(1);
    ncnn::set_omp_num_threads(1);
    return JNI_VERSION_1_6;
}
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FaceNative", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FaceNative", __VA_ARGS__)

static FaceDetector g_faceDetector;

// 将 Java 层 Bitmap 转为 ncnn::Mat，并进行 letterbox 缩放、填充、归一化
ncnn::Mat bitmap_to_ncnn(JNIEnv* env, jobject bitmap) {

    ncnn::set_omp_num_threads(1);

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceDetector", "AndroidBitmap_getInfo failed");
        return ncnn::Mat();
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceDetector", "AndroidBitmap_lockPixels failed");
        return ncnn::Mat();
    }

    int w = info.width;
    int h = info.height;

    __android_log_print(ANDROID_LOG_ERROR,
                        "FaceDetector","Original image size: %d x %d", w, h);
    // 目标尺寸（假设模型输入为 640x640，根据需要调整）
    const int target_size = 640;
    // 计算缩放比例
    float scale = target_size / (float)std::max(w, h);
    int resize_w = (int)(w * scale);
    int resize_h = (int)(h * scale);

    // 从 Bitmap 像素创建 ncnn::Mat（假设 Bitmap 格式为 RGBA）
    ncnn::Mat in = ncnn::Mat::from_pixels_resize(
            (const unsigned char*)pixels,
            ncnn::Mat::PIXEL_RGBA2BGR,//
            w, h,
            resize_w, resize_h);

    AndroidBitmap_unlockPixels(env, bitmap);

    // 填充边界，使用常数114（灰色）
    int wpad = target_size - resize_w;
    int hpad = target_size - resize_h;
    int top = hpad / 2;
    int bottom = hpad - top;
    int left = wpad / 2;
    int right = wpad - left;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, top, bottom, left, right, ncnn::BORDER_CONSTANT, 114.f);

    // BGR->RGB 归一化（模型可能已经调整顺序，此处仅归一化）
    const float norm_vals[3] = {1/255.f, 1/255.f, 1/255.f};
    in_pad.substract_mean_normalize(nullptr, norm_vals);

    return in_pad;
}



extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_facedemo_FaceNative_init(JNIEnv* env, jclass cls, jobject assetManager) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) return JNI_FALSE;
    // 加载模型
    int ret = g_faceDetector.loadModel(mgr);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_example_facedemo_FaceNative_detectBitmap(JNIEnv* env, jclass cls, jobject bitmap, jfloat threshold) {

    if (bitmap == nullptr) return nullptr;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    int orig_w = info.width;
    int orig_h = info.height;

    // 将 Bitmap 转为 ncnn::Mat
    ncnn::Mat in = bitmap_to_ncnn(env, bitmap);

    // 调用人脸检测
    std::vector<FaceInfo> faces;
    g_faceDetector.detect(in, faces, threshold, orig_w, orig_h);


    // 将结果转换为 FaceRect[] 返回给 Java
  jclass faceRectCls = env->FindClass("com/example/facedemo/FaceRect");
    if (!faceRectCls) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "FindClass FaceRect failed");
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(faceRectCls, "<init>", "(FFFFF)V");
    if (!constructor) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "GetMethodID FaceRect.<init> failed");
        return nullptr;
    }
    jobjectArray jfaces = env->NewObjectArray(faces.size(), faceRectCls, nullptr);
    for (size_t i = 0; i < faces.size(); i++) {
        const FaceInfo &f = faces[i];
        jobject obj = env->NewObject(faceRectCls, constructor, f.x, f.y, f.w, f.h, f.score);
        env->SetObjectArrayElement(jfaces, i, obj);
        env->DeleteLocalRef(obj); // 释放循环内局部引用，避免大量对象时累积
    }
    env->DeleteLocalRef(faceRectCls); // 清理局部引用
    return jfaces;
}


