package com.example.facedemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.Image.Plane;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * ImageUtil:
 * - 将 ImageProxy 的 YUV_420_888 -> NV21 字节数组（拼接）
 * - 使用 YuvImage.compressToJpeg -> BitmapFactory.decodeByteArray
 *
 * 注意：不同设备的 plane stride 和 UV 排列可能不同。如果颜色异常，请尝试把 U/V 顺序对调。
 */
public class ImageUtil {
    private static final String TAG = "ImageUtil";
    private static YuvToRgbConverter yuvConverter;

    // 必须在主线程用 Context 初始化一次（例如在 MainActivity.onCreate）
    public static void init(Context context) {
        if (yuvConverter == null) {
            try {
                yuvConverter = new YuvToRgbConverter(context);
                Log.i(TAG, "YuvToRgbConverter initialized");
            } catch (Throwable t) {
                Log.w(TAG, "YuvToRgbConverter init failed, will fallback to JPEG method", t);
                yuvConverter = null;
            }
        }
    }

    public static void destroy() {
        try {
            if (yuvConverter != null) {
                yuvConverter.destroy();
                yuvConverter = null;
                Log.i(TAG, "YuvToRgbConverter destroyed");
            }
        } catch (Throwable t) {
            Log.w(TAG, "YuvToRgbConverter destroy failed", t);
        }
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    public static Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        if (imageProxy == null) return null;
        Image image = imageProxy.getImage();
        if (image == null) return null;

        // 优先使用高效转换（RenderScript）
        if (yuvConverter != null) {
            try {
                int width = image.getWidth();
                int height = image.getHeight();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                // 加 try/catch 防止中途失败导致 NPE
                yuvConverter.yuvToRgb(image, bitmap);
                Log.d(TAG, "imageProxyToBitmap: used yuvConverter");
                return bitmap;
            } catch (Throwable t) {
                Log.w(TAG, "yuvConverter.yuvToRgb failed, falling back to JPEG method", t);
                // fall through to fallback
            }
        } else {
            Log.w(TAG, "yuvConverter is null, falling back to JPEG method");
        }

        // 回退：NV21 -> JPEG -> Bitmap（慢，但兼容）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] nv21 = yuv420ToNv21(image);
            int width = image.getWidth();
            int height = image.getHeight();
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            // 85 是一个折衷的质量参数：既保证画质又减少大小
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 85, out);
            byte[] jpegBytes = out.toByteArray();
            Log.d(TAG, "imageProxyToBitmap: used JPEG fallback");
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Throwable e) {
            Log.e(TAG, "imageProxyToBitmap failed (both methods)", e);
            return null;
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] yuv420ToNv21(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] y = new byte[ySize];
        byte[] u = new byte[uSize];
        byte[] v = new byte[vSize];

        yBuffer.get(y);
        uBuffer.get(u);
        vBuffer.get(v);

        byte[] nv21 = new byte[ySize + uSize + vSize];

        // copy Y
        System.arraycopy(y, 0, nv21, 0, ySize);

        // interleave VU (NV21 = Y + VU VU ...)
        int pos = ySize;
        for (int i = 0; i < vSize && pos + 1 < nv21.length; i++) {
            nv21[pos++] = v[i];
            if (pos < nv21.length) nv21[pos++] = u[i];
        }
        return nv21;
    }
}