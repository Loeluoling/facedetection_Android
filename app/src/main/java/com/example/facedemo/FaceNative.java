package com.example.facedemo;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public class FaceNative {
    static {
        System.loadLibrary("native-lib");
    }

    // 初始化模型，传入 AssetManager 以加载 assets 下的模型文件
    public static native boolean init(AssetManager assetManager);

    // 从 Bitmap 进行人脸检测，threshold 为置信度门限
    public static native FaceRect[] detectBitmap(Bitmap bitmap, float threshold);
}

