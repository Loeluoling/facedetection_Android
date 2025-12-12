package com.example.facedemo; // 请用你项目实际包名

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraFrameAnalyzer implements ImageAnalysis.Analyzer {
    private static final String TAG = "CameraFrameAnalyzer";

    public interface Callback {
        void onFaceCropped(Bitmap faceBitmap, Rect faceRect);
    }

    private final Callback callback;
    private final ExecutorService backgroundExecutor;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    // 控制是否启用检测与节流
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final long minIntervalMillis;
    private volatile long lastAnalyseTime = 0L;

    // 本地推理并发保护：只允许一个线程进入 native 推理（libncnn）
    private final Semaphore nativeSemaphore;

    /**
     * 旧版构造器（会创建内部 single-thread executor）
     */
    public CameraFrameAnalyzer(Semaphore nativeSemaphore, Callback callback) {
        this(nativeSemaphore, callback, 3000, null);
    }

    /**
     * 可设置节流间隔的构造器（会创建内部 executor）
     */
    public CameraFrameAnalyzer(Semaphore nativeSemaphore, Callback callback, long minIntervalMillis) {
        this(nativeSemaphore, callback, minIntervalMillis, null);
    }

    /**
     * 推荐构造器：可以传入一个共享的 ExecutorService（避免每个 analyzer 都 new 一个线程池）
     * 如果 executor==null，会内部创建单线程 executor。
     */
    public CameraFrameAnalyzer(Semaphore nativeSemaphore, Callback callback, long minIntervalMillis, ExecutorService executor) {
        if (nativeSemaphore == null) throw new IllegalArgumentException("nativeSemaphore required");
        this.nativeSemaphore = nativeSemaphore;
        this.callback = callback;
        this.minIntervalMillis = Math.max(50, minIntervalMillis);
        if (executor != null) {
            this.backgroundExecutor = executor;
        } else {
            this.backgroundExecutor = Executors.newSingleThreadExecutor();
        }
    }

    public void setEnabled(boolean e) { enabled.set(e); }
    public boolean isEnabled() { return enabled.get(); }

    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        // 如果未启用，立即丢帧
        if (!enabled.get()) {
            imageProxy.close();
            return;
        }

        // 节流（基于 elapsedRealtime）
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnalyseTime < minIntervalMillis) {
            imageProxy.close();
            return;
        }
        lastAnalyseTime = now;

        // 丢帧策略：避免并发进入后台 Executor 的排队导致过多任务
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        backgroundExecutor.submit(() -> {
            boolean locked = false;
            try {
                // 转换 ImageProxy -> Bitmap
                Bitmap bmp = ImageUtil.imageProxyToBitmap(imageProxy);
                if (bmp == null) {
                    Log.w(TAG, "bitmap is null");
                    return;
                }

                int rotation = imageProxy.getImageInfo().getRotationDegrees();
                Bitmap rotated = rotateBitmapIfNeeded(bmp, rotation);
                if (rotated != bmp) {
                    // 释放原始 bmp（rotated 已为新对象）
                    try { bmp.recycle(); } catch (Throwable ignored) {}
                }

                if (rotated == null || rotated.getWidth() <= 0 || rotated.getHeight() <= 0) {
                    Log.w(TAG, "rotated bitmap invalid");
                    try { if (rotated != null && !rotated.isRecycled()) rotated.recycle(); } catch (Throwable ignored) {}
                    return;
                }

                // defensive: ensure ARGB_8888 for native
                if (rotated.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap tmp = rotated.copy(Bitmap.Config.ARGB_8888, false);
                    try { if (!rotated.isRecycled()) rotated.recycle(); } catch (Throwable ignored) {}
                    rotated = tmp;
                    if (rotated == null) {
                        Log.w(TAG, "failed to convert rotated to ARGB_8888");
                        return;
                    }
                }

                // 尝试获取 native 信号量；若获取不到，安全丢帧并回收 rotated
//                if (!nativeSemaphore.tryAcquire()) {
//                    Log.w(TAG, "native busy, dropping this frame");
//                    try { if (rotated != null && !rotated.isRecycled()) rotated.recycle(); } catch (Throwable ignored) {}
//                    return;
//                }
//                locked = true;

                // 获取 native 信号量（阻塞模式）
                Log.d(TAG, "native acquire attempt thread=" + Thread.currentThread().getName());
                try {
                    nativeSemaphore.acquire();  // 改为阻塞获取
                    locked = true;
                    Log.d(TAG, "native acquired thread=" + Thread.currentThread().getName());
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for semaphore", e);
                    try { if (rotated != null && !rotated.isRecycled()) rotated.recycle(); } catch (Throwable ignored) {}
                    return;
                }


                // 调用 native 检测（确保在持有锁时执行）
                FaceRect[] faces = null;
                try {
                    faces = FaceNative.detectBitmap(rotated, 0.35f);
                } catch (Throwable t) {
                    Log.e(TAG, "FaceNative.detectBitmap crashed", t);
                } finally {
                    // 注意：不要在这里回收 rotated —— 我们在下面统一在所有路径回收它
                    // 释放 native 锁在 finally 里（见下）
                }

                if (faces == null || faces.length == 0) {
                    return;
                }

                // 对检测到的每个 face 裁切并回调
                for (FaceRect f : faces) {
                    if (f == null) continue;
                    int left = Math.max(0, Math.min((int) f.x, rotated.getWidth() - 1));
                    int top = Math.max(0, Math.min((int) f.y, rotated.getHeight() - 1));
                    int right = Math.max(0, Math.min((int) (f.x + f.w), rotated.getWidth()));
                    int bottom = Math.max(0, Math.min((int) (f.y + f.h), rotated.getHeight()));
                    if (right <= left || bottom <= top) continue;

                    Bitmap faceCrop = null;
                    Bitmap faceCopy = null;
                    try {
                        int w = right - left;
                        int h = bottom - top;
                        if (w <= 0 || h <= 0) continue;

                        faceCrop = Bitmap.createBitmap(rotated, left, top, w, h);
                        if (faceCrop == null) continue;

                        try {
                              Bitmap.Config cfg = faceCrop.getConfig() != null ? faceCrop.getConfig() : Bitmap.Config.ARGB_8888;
                            faceCopy = faceCrop.copy(cfg, true);
                        } catch (Throwable copyEx) {
                            Log.w(TAG, "face bitmap copy failed, passing original", copyEx);
                            faceCopy = faceCrop;
                            faceCrop = null; // 表示不要在这里回收
                        }

                        // 将 faceCopy 交给上层 callback（由上层负责管理它的生命周期）
                        try {
                            callback.onFaceCropped(faceCopy, new Rect(left, top, right, bottom));
                        } catch (Throwable cbEx) {
                            Log.e(TAG, "callback.onFaceCropped error", cbEx);
                        }

                    } finally {
                        if (faceCrop != null && !faceCrop.isRecycled()) {
                            try { faceCrop.recycle(); } catch (Throwable ignored) {}
                        }
                        // faceCopy 交给回调方管理，不在这里回收
                    }
                }

                // 所有裁切/回调完毕，安全释放 rotated
                if (rotated != null && !rotated.isRecycled()) {
                    try { rotated.recycle(); } catch (Throwable ignored) {}
                }

            } catch (Throwable t) {
                Log.e(TAG, "analyze error", t);
            } finally {
                // 无论如何释放 native 锁（如果我们确实拿到过）
                if (locked) {
                    try { nativeSemaphore.release(); } catch (Throwable ignored) {}
                }
                try { imageProxy.close(); } catch (Throwable ignored) {}
                busy.set(false);
            }
        });

    }

    private Bitmap rotateBitmapIfNeeded(Bitmap src, int rotationDegrees) {
        if (rotationDegrees == 0) return src;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
        return rotated;
    }

    public void shutdown() {
        enabled.set(false);
        try {
            backgroundExecutor.shutdownNow();
        } catch (Throwable ignored) {}
    }
}
