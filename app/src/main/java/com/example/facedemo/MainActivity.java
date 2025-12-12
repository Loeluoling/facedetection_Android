package com.example.facedemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import android.graphics.Rect;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraFilter;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.huawei.hms.mlsdk.common.MLFrame;
import com.huawei.hms.mlsdk.faceverify.MLFaceTemplateResult;
import com.huawei.hms.mlsdk.faceverify.MLFaceVerificationAnalyzer;
import com.huawei.hms.mlsdk.faceverify.MLFaceVerificationAnalyzerFactory;
import com.huawei.hms.mlsdk.faceverify.MLFaceVerificationAnalyzerSetting;
import com.huawei.hms.mlsdk.faceverify.MLFaceVerificationResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.facedemo.AppDatabase;
import com.example.facedemo.UserDao;
import com.example.facedemo.LockerDao;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private PreviewView previewView;
    private TextView resultTextView;
    private Button registerButton;
    // 数据库相关
    private AppDatabase db;
    private UserDao userDao;
    private LockerDao lockerDao;
    // 用于存储 templateId 到 userId 的映射
    private Map<Integer, String> templateIdToUserId = new ConcurrentHashMap<>();
    private final ExecutorService templateExecutor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean hasTemplate = new AtomicBoolean(false);

    private Button startDetectButton;
    private final AtomicBoolean isDetecting = new AtomicBoolean(false);

    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private CameraFrameAnalyzer cameraAnalyzer;

    // HMS face verification analyzer
    private MLFaceVerificationAnalyzer verifier;

    // 保存最近裁切的人脸（方便用户点击注册模板）
    private volatile Bitmap lastDetectedFace = null;

    // 控制 HMS 并发
    private final Semaphore hmsSemaphore = new Semaphore(1);
    // 控制 native(ncnn) 并发
    private final Semaphore nativeSemaphore = new Semaphore(1);
    // 权限申请 launcher
    private ActivityResultLauncher<String> requestPermissionLauncher;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_face_recognition);


        ImageUtil.init(this);
        previewView = findViewById(R.id.previewView);
        resultTextView = findViewById(R.id.resultTextView);
        registerButton = findViewById(R.id.registerButton);

        // 初始化数据库
        db = AppDatabase.getInstance(this);
        userDao = db.userDao();
        lockerDao = db.lockerDao();

        // 启动时恢复模板
        new Handler().postDelayed(() -> {
            templateExecutor.submit(this::restoreTemplatesOnStartup);
        }, 1000);

        startDetectButton = findViewById(R.id.startDetectButton);
        startDetectButton.setOnClickListener(v -> {
            boolean now = !isDetecting.get();
            isDetecting.set(now);
            if (cameraAnalyzer != null) {
                cameraAnalyzer.setEnabled(now);
            }
            startDetectButton.setText(now ? "停止检测" : "开始检测");
            Toast.makeText(MainActivity.this, now ? "开始检测已打开" : "检测已停止", Toast.LENGTH_SHORT).show();
        });

        // 1) 本地模型初始化（你已有的 JNI 接口）
        boolean initOk = FaceNative.init(getAssets());
        if (!initOk) {
            Toast.makeText(this, "本地模型加载失败 (FaceNative.init)", Toast.LENGTH_LONG).show();
            Log.e(TAG, "FaceNative.init returned false");
        } else {
            Log.i(TAG, "FaceNative.init success");
        }

        // 2) 初始化 HMS FaceVerificationAnalyzer（更稳妥的初始化）
        try {
            MLFaceVerificationAnalyzerSetting.Factory factory = new MLFaceVerificationAnalyzerSetting.Factory();
            factory.setMaxFaceDetected(2);
            MLFaceVerificationAnalyzerSetting setting = factory.create();
            try {
                verifier = MLFaceVerificationAnalyzerFactory.getInstance().getFaceVerificationAnalyzer(setting);
                Log.i(TAG, "HMS verifier init OK (with setting)");
            } catch (Throwable inner) {
                // 再试一次更宽松的 API（部分设备/环境会在此抛错）
                try {
                    verifier = MLFaceVerificationAnalyzerFactory.getInstance().getFaceVerificationAnalyzer();
                    Log.i(TAG, "HMS verifier init OK (fallback)");
                } catch (Throwable inner2) {
                    verifier = null;
                    Log.w(TAG, "HMS verifier initialization failed (both attempts). Will use local fallback.", inner2);
                }
            }
        } catch (Throwable t) {
            verifier = null;
            Log.w(TAG, "HMS verifier setup unexpected failure. Will use local fallback.", t);
        }


        // 3) 权限申请（Camera）
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        // 延迟一点启动相机，确保权限完全生效
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            startCamera();
                        }, 500);
                    } else {
                        Toast.makeText(MainActivity.this,
                                "需要摄像头权限才能使用人脸识别功能", Toast.LENGTH_LONG).show();
                    }
                }
        );

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

// 在 MainActivity.onCreate 中替换现有 registerButton listener 为下面这个
        registerButton.setOnClickListener(v -> {
            if (lastDetectedFace == null || lastDetectedFace.isRecycled()) {
                Toast.makeText(MainActivity.this, "尚未检测到人脸，无法注册模板", Toast.LENGTH_SHORT).show();
                return;
            }

            // 弹出对话框让用户输入 userId（例如 alice）
            final EditText input = new EditText(MainActivity.this);
            input.setHint("输入用户ID (例如 alice)");
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("注册模板")
                    .setMessage("请输入用户ID以保存为模板：")
                    .setView(input)
                    .setCancelable(true)
                    .setPositiveButton("确定", (dialog, which) -> {
                        String userId = input.getText() != null ? input.getText().toString().trim() : "";
                        if (userId.isEmpty()) {
                            Toast.makeText(MainActivity.this, "用户ID不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // 暂停检测，避免在注册期间 analyzer 替换/回收图片
                        if (cameraAnalyzer != null) cameraAnalyzer.setEnabled(false);

                        // 复制 bitmap 并在后台保存到文件 + 写 DB + 调用 SDK
                        Bitmap templateCopy;
                        try {
                            Bitmap.Config cfg = lastDetectedFace.getConfig() != null ? lastDetectedFace.getConfig() : Bitmap.Config.ARGB_8888;
                            templateCopy = lastDetectedFace.copy(cfg, true);
                        } catch (Throwable t) {
                            Log.w(TAG, "copy lastDetectedFace failed", t);
                            templateCopy = lastDetectedFace;
                        }

                        final Bitmap toSet = templateCopy;
                        templateExecutor.submit(() -> {
                            try {
                                // 1) 调用原来的 setTemplateFromBitmap 去注册到 SDK（如你需要）
                                setTemplateFromBitmap(toSet);

                                // 2) 保存模板图片到内部文件并写入 Room（userDao 在你已初始化）
                                String path = saveTemplateImageToFile(toSet, userId); // 请确保此方法存在于类中
                                if (path != null) {
                                    long now = System.currentTimeMillis();
                                    UserEntity user = new UserEntity(userId, userId /*name*/, path, null, now);
                                    userDao.insert(user);
                                    Log.i(TAG, "Saved template to " + path + " and inserted user " + userId);
                                } else {
                                    Log.e(TAG, "Failed to save template image for user " + userId);
                                }
                            } finally {
                                // 回到 UI 线程提示结果并可以选择是否重新启用检测（由你决定）
                                runOnUiThread(() -> {
                                    Toast.makeText(MainActivity.this, "模板注册流程已完成（" + userId + "）", Toast.LENGTH_SHORT).show();
                                    // 可以自动回到检测就绪状态：启用检测或保持禁用
                                    // cameraAnalyzer.setEnabled(true);
                                });

                                // 如果我们拷贝了独立的副本并且不是 lastDetectedFace，则回收
                                if (toSet != lastDetectedFace && toSet != null && !toSet.isRecycled()) {
                                    try {
                                        toSet.recycle();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        });

                    })
                    .setNegativeButton("取消", (dialog, which) -> {
                        dialog.dismiss();
                    })
                    .show();
        });
    }


    private final AtomicInteger localTemplateIdCounter = new AtomicInteger(-1);
    // 用于本地降级比对时缓存模板图片（若用户少，可以缓存）
    private final Map<Integer, Bitmap> localTemplateCache = new ConcurrentHashMap<>();

    //添加启动时恢复模板的方法
    private void restoreTemplatesOnStartup() {
        try {
            List<UserEntity> users = userDao.getAll(); // Room 在后台线程安全调用
            if (users == null || users.isEmpty()) {
                Log.i(TAG, "No local templates found");
                hasTemplate.set(false);
                return;
            }

            // 先清空内存映射，防止旧数据干扰
            templateIdToUserId.clear();
            localTemplateCache.clear();

            // 添加信号量控制，避免并发问题
            if (!hmsSemaphore.tryAcquire(5000, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timeout waiting for HMS semaphore during restore");
                hasTemplate.set(false);
                return;
            }

            int restoredCount = 0;

            try {
                for (UserEntity user : users) {
                    if (user == null) continue;

                    // 如果数据库里没有 path，直接跳过
                    String path = user.templatePath;
                    if (path == null || path.isEmpty()) {
                        Log.i(TAG, "User " + user.userId + " has no templatePath, skipping.");
                        continue;
                    }

                    File f = new File(path);
                    if (!f.exists()) {
                        // 文件在磁盘上已被删除 -> 清理 DB 中对应字段（保持用户记录，但去除 template 指向）
                        Log.w(TAG, "Template file missing for user " + user.userId + " path=" + path + ". Clearing DB fields.");
                        user.templatePath = null;
                        user.templateId = null;
                        userDao.update(user);
                        continue;
                    }

                    // 解码时 downsample，避免大图占用太多内存
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    // 先测量大小，再按比例缩放。下面简单设置 inSampleSize 为 1（可改）
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, opts);
                    int inW = opts.outWidth;
                    int inH = opts.outHeight;
                    opts.inJustDecodeBounds = false;
                    // 目标缩放长边为 128（可改为 64 更省内存）
                    int target = 128;
                    int inSampleSize = 1;
                    if (inW > target || inH > target) {
                        final int halfW = inW / 2;
                        final int halfH = inH / 2;
                        while ((halfW / inSampleSize) >= target && (halfH / inSampleSize) >= target) {
                            inSampleSize *= 2;
                        }
                    }
                    opts.inSampleSize = inSampleSize;

                    Bitmap bmp = null;
                    try {
                        bmp = BitmapFactory.decodeFile(path, opts);
                        if (bmp == null) {
                            Log.w(TAG, "Failed decoding template image (null bitmap): " + path);
                            // 若解码失败，清理 DB reference，避免下次重复报错
                            user.templatePath = null;
                            user.templateId = null;
                            userDao.update(user);
                            continue;
                        }

                        if (verifier != null) {
                            // 如果 verifier 可用，尝试注册到 HMS（同步/现有行为）
                            try {
                                MLFrame frame = MLFrame.fromBitmap(bmp);
                                @SuppressWarnings("unchecked")
                                List<MLFaceTemplateResult> res = verifier.setTemplateFace(frame);
                                if (res != null && !res.isEmpty()) {
                                    Integer templateId = res.get(0).getTemplateId();
                                    if (templateId != null) {
                                        user.templateId = templateId;
                                        // 保持 templatePath（因为文件存在）
                                        userDao.update(user);
                                        templateIdToUserId.put(templateId, user.userId);
                                        restoredCount++;
                                        Log.i(TAG, "恢复模板映射: templateId=" + templateId + " -> userId=" + user.userId);
                                    } else {
                                        Log.w(TAG, "HMS setTemplateFace returned null templateId for user " + user.userId);
                                    }
                                } else {
                                    Log.w(TAG, "setTemplateFace returned empty for " + user.userId);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "Failed to register template to HMS for user " + user.userId, t);
                                // 发生异常时回退到本地缓存（降级）
                                int localId = localTemplateIdCounter.getAndDecrement();
                                templateIdToUserId.put(localId, user.userId);
                                // 缓存缩小后的 bmp（注意内存）
                                localTemplateCache.put(localId, bmp);
                                user.templateId = localId;
                                userDao.update(user);
                                restoredCount++;
                                // 不在这里 recycle bmp，因为已经缓存
                                continue;
                            } finally {
                                // 如果已经把 bmp 存进 localTemplateCache，就不要 recycle；在上述回退分支已经 continue
                                // 到这里如果 verifier 成功或只是查看，则需要 recycle bmp
                                if (bmp != null) {
                                    if (!localTemplateCache.containsValue(bmp)) {
                                        if (!bmp.isRecycled()) bmp.recycle();
                                    }
                                }
                            }
                        } else {
                            // verifier == null: 本地降级 —— 使用本地 ID（负数）并缓存缩小后的 bitmap 以便本地比对（注意内存）
                            int localId = localTemplateIdCounter.getAndDecrement(); // -1, -2 ...
                            templateIdToUserId.put(localId, user.userId);
                            localTemplateCache.put(localId, bmp); // 缓存 bitmap 以便快速匹配（注意内存）
                            user.templateId = localId;
                            userDao.update(user);
                            restoredCount++;
                            // 不要在这里 recycle bmp，因为缓存持有它
                        }
                    } catch (Throwable decodeEx) {
                        Log.w(TAG, "Exception while handling template image for user " + user.userId, decodeEx);
                        // 尝试清理 DB 以避免下次重复出错
                        user.templatePath = null;
                        user.templateId = null;
                        userDao.update(user);
                        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                    }
                } // for users

                boolean success = restoredCount > 0;
                hasTemplate.set(success);
                Log.i(TAG, "模板恢复完成，共加载 " + templateIdToUserId.size() + " 个模板映射");
            } finally {
                hmsSemaphore.release();
            }
        } catch (InterruptedException e) {
            Log.w(TAG, "模板恢复被中断", e);
            hasTemplate.set(false);
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "restoreTemplatesOnStartup failed", t);
            hasTemplate.set(false);
        }
    }

    private void startCamera() {
        try {
            ProcessCameraProvider.getInstance(this).addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = ProcessCameraProvider.getInstance(this).get();

                    // 检查可用摄像头
                    if (cameraProvider.getAvailableCameraInfos().isEmpty()) {
                        Log.e(TAG, "No cameras available");
                        Toast.makeText(this, "没有检测到摄像头", Toast.LENGTH_LONG).show();
                        return;
                    }

                    Preview preview = new Preview.Builder().build();

                    // 简化的摄像头选择逻辑
                    CameraSelector cameraSelector = findUsableCamera(cameraProvider);

                    if (cameraSelector == null) {
                        Log.e(TAG, "No suitable camera selector found");
                        Toast.makeText(this, "无法找到合适的摄像头", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // 降低分辨率要求，提高兼容性
                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(320, 240)) // 降低分辨率
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();
                    final CameraFrameAnalyzer[] analyzerHolder = new CameraFrameAnalyzer[1];

                    CameraFrameAnalyzer localAnalyzer = new CameraFrameAnalyzer(nativeSemaphore,
                            (faceBitmap, faceRect) -> {
                        runOnUiThread(() -> {
                            try {
                                synchronized (this) {
                                    if (lastDetectedFace != null && !lastDetectedFace.isRecycled()) {
                                        lastDetectedFace.recycle();
                                    }
                                // 将人脸裁切缩放到小尺寸作为模板，减少内存和传输负担
                                Bitmap small = Bitmap.createScaledBitmap(faceBitmap,
                                        160, 160, true);
                                    lastDetectedFace = small.copy(small.getConfig(), true);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "copy lastDetectedFace failed", t);
                                lastDetectedFace = faceBitmap;
                            }
                            CameraFrameAnalyzer a = analyzerHolder[0];
                            if (a != null) {
                                a.setEnabled(false);
                            } else {
                                // 极端保护：如果holder没有值，尝试访问字段（此时应该已经赋值）
                                if (cameraAnalyzer != null) {
                                    cameraAnalyzer.setEnabled(false);
                                }
                            }
                            sendToHmsVerifier(lastDetectedFace);
                        });
                            }, 1500 /* ms */);


                    // 先把实例放入holder，这样lambda回调时就能安全访问
                    analyzerHolder[0] = localAnalyzer;

                    // 初始为false（刚创建时），按钮打开后才setEnabled(true)
                    localAnalyzer.setEnabled(isDetecting.get());

                    // 使用局部变量设置analyzer，不要用未赋值的字段
                    imageAnalysis.setAnalyzer(cameraExecutor, localAnalyzer);

                    // 最后才赋值给类字段，确保其他地方可以引用
                    cameraAnalyzer = localAnalyzer;
                    // === 修复部分结束 ===

                    cameraProvider.unbindAll();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    // 尝试绑定摄像头
                    try {
                        Camera camera = cameraProvider.bindToLifecycle(this,
                                cameraSelector, preview, imageAnalysis);
                        if (camera != null) {
                            Log.i(TAG, "Camera started successfully");
                            Toast.makeText(this, "摄像头启动成功", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "bindToLifecycle failed", e);
                        // 尝试只绑定预览，不绑定图像分析
                        try {
                            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
                            Log.i(TAG, "Camera preview started (without analysis)");
                            Toast.makeText(this, "摄像头预览已启动", Toast.LENGTH_SHORT).show();
                        } catch (Exception e2) {
                            Log.e(TAG, "Even preview-only binding failed", e2);
                            throw e2;
                        }
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Camera setup failed", e);
                    Toast.makeText(this, "相机启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }, ContextCompat.getMainExecutor(this));

        } catch (Exception e) {
            Log.e(TAG, "startCamera failed", e);
            Toast.makeText(this, "启动相机异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 查找可用的摄像头
     */

    private CameraSelector findUsableCamera(ProcessCameraProvider cameraProvider) {
        // 1) 尝试后置摄像头
//        CameraSelector back = CameraSelector.DEFAULT_BACK_CAMERA;
//        try {
//            if (cameraProvider.hasCamera(back)) {
//                Log.d(TAG, "Using back camera");
//                return back;
//            }
//        } catch (CameraInfoUnavailableException e) {
//            Log.w(TAG, "Back camera info unavailable", e);
//        } catch (Exception e) {
//            Log.w(TAG, "hasCamera(back) unexpected error", e);
//        }
//
//        // 2) 尝试前置摄像头
//        CameraSelector front = CameraSelector.DEFAULT_FRONT_CAMERA;
//        try {
//            if (cameraProvider.hasCamera(front)) {
//                Log.d(TAG, "Using front camera");
//                return front;
//            }
//        } catch (CameraInfoUnavailableException e) {
//            Log.w(TAG, "Front camera info unavailable", e);
//        } catch (Exception e) {
//            Log.w(TAG, "hasCamera(front) unexpected error", e);
//        }

        // 3) 如果前/后都不行，尝试直接使用第一个可用的 CameraInfo（兼容 USB/特殊设备）
        try {
            List<CameraInfo> infos = cameraProvider.getAvailableCameraInfos();
            if (infos != null && !infos.isEmpty()) {
                // 构造一个 CameraSelector，filter 返回第一个 cameraInfo（最保守的降级方案）
                CameraSelector selector = new CameraSelector.Builder()
                        .addCameraFilter(new CameraFilter() {
                            @NonNull
                            @Override
                            public List<CameraInfo> filter(@NonNull List<CameraInfo> cameraInfos) {
                                if (cameraInfos == null || cameraInfos.isEmpty())
                                    return Collections.emptyList();
                                // 只保留第一个 CameraInfo（你也可以增加更复杂的过滤条件）
                                return Collections.singletonList(cameraInfos.get(0));
                            }
                        }).build();

                try {
                    if (cameraProvider.hasCamera(selector)) {
                        Log.d(TAG, "Using first available CameraInfo via CameraFilter");
                        return selector;
                    } else {
                        Log.w(TAG, "Selector built from first CameraInfo reports no camera");
                    }
                } catch (CameraInfoUnavailableException e) {
                    Log.w(TAG, "CameraInfo unavailable when checking default selector", e);
                    // 即使这里抛出，我们仍然可以返回 selector 去尝试 bind（bind 会再次尝试并给出更详细错误）
                    return selector;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getAvailableCameraInfos failed or returned empty", e);
        }

        // 4) 最后尝试一个不带 lensFacing 要求的默认 selector（守底）
        try {
            CameraSelector defaultSel = new CameraSelector.Builder().build();
            try {
                if (cameraProvider.hasCamera(defaultSel)) {
                    Log.d(TAG, "Using default camera selector");
                    return defaultSel;
                }
            } catch (CameraInfoUnavailableException e) {
                Log.w(TAG, "Default selector camera info unavailable", e);
                // 返回 defaultSel 让 bind 去尝试（bind 时可能给出更具体异常）
                return defaultSel;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to build default CameraSelector", e);
        }

        Log.e(TAG, "No usable camera found");
        return null;
    }
    
//添加保存模板图片的方法
    private String saveTemplateImageToFile(Bitmap bmp, String userId) {
        try {
            File dir = new File(getFilesDir(), "face_templates");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create templates directory");
                return null;
            }

            File file = new File(dir, userId + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                boolean success = bmp.compress(Bitmap.CompressFormat.PNG, 90, out);
                if (!success) {
                    Log.e(TAG, "Failed to compress bitmap");
                    return null;
                }
                out.flush();
            }
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "saveTemplateImageToFile failed", e);
            return null;
        }
    }

//添加用户ID输入对话框
    private void showUserIdInputDialog(Bitmap templateBitmap, Integer templateId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("注册用户");

        final EditText input = new EditText(this);
        input.setHint("输入用户ID");
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String userId = input.getText().toString().trim();
            if (userId.isEmpty()) {
                Toast.makeText(MainActivity.this, "用户ID不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            // 在后台线程保存用户信息
            templateExecutor.submit(() -> saveUserTemplate(userId, templateBitmap, templateId));
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

// 添加保存用户模板的方法
    private void saveUserTemplate(String userId, Bitmap templateBitmap, Integer templateId) {
        try {
            // 保存图片到文件
            String templatePath = saveTemplateImageToFile(templateBitmap, userId);

            if (templatePath != null) {
                // 创建用户实体并保存到数据库
                UserEntity user = new UserEntity(userId, userId, templatePath, templateId, System.currentTimeMillis());
                userDao.insert(user);

                // 更新内存映射
                if (templateId != null) {
                    templateIdToUserId.put(templateId, userId);
                    Log.i(TAG, "注册模板映射: templateId=" + templateId + " -> userId=" + userId);
                }

                Log.i(TAG, "用户注册成功: " + userId + ", 模板路径: " + templatePath);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "用户 " + userId + " 注册成功", Toast.LENGTH_SHORT).show());
            } else {
                Log.e(TAG, "保存模板图片失败");
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "保存模板失败", Toast.LENGTH_SHORT).show());
            }
        } catch (Exception e) {
            Log.e(TAG, "保存用户模板失败", e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "注册失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * 将指定 bitmap 设为 HMS 模板（同步 API 返回模板结果列表）
     */
    private void setTemplateFromBitmap(Bitmap bmp) {
            if (bmp == null) {
                runOnUiThread(() -> Toast.makeText
                        (MainActivity.this, "模板图为空", Toast.LENGTH_SHORT).show());
                return;
            }

            // 复制一份，确保原始 lastDetectedFace 不被影响
            Bitmap bmpCopy = null;
            try {
                // 统一用 ARGB_8888，setTemplateFace 需要可读取像素的 bitmap
                Bitmap.Config cfg = bmp.getConfig() != null ? bmp.getConfig() : Bitmap.Config.ARGB_8888;
                bmpCopy = bmp.copy(cfg, true);
            } catch (Throwable t) {
                Log.w(TAG, "copy template bitmap failed, will try using original", t);
                bmpCopy = bmp; // 兜底（若拷贝失败就用原图，注意后续不要 recycle 原图）
            }

                // === 新增：如果没有 HMS verifier，走本地保存降级路径 ===
            if (verifier == null) {
                    // 快速保存文件并写 DB
                String userIdCandidate = "local_" + System.currentTimeMillis();
                String path = saveTemplateImageToFile(bmpCopy, userIdCandidate);
                if (path != null) {
                    int localId = localTemplateIdCounter.getAndDecrement();
                    UserEntity user = new UserEntity(userIdCandidate, userIdCandidate, path, localId, System.currentTimeMillis());
                    userDao.insert(user);
                    templateIdToUserId.put(localId, userIdCandidate);
                    localTemplateCache.put(localId, bmpCopy); // 缓存
                    hasTemplate.set(true);
                    final Integer finalLocalId = localId;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "本地模板设置成功 (id=" + finalLocalId + ")",
                            Toast.LENGTH_SHORT).show());
                    Log.i(TAG, "Local template saved: id=" + localId + " path=" + path);
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "本地模板保存失败", Toast.LENGTH_SHORT).show());
                }
                return; // 本地路径结束，直接返回
            }

        // verifier != null：需要与 HMS 串行化 —— 使用 hmsSemaphore（而不是 nativeSemaphore）
            boolean locked = false;
            try {
                // 等待一小会儿拿到 hmsSemaphore（避免与其他 HMS 请求并发）
                locked = hmsSemaphore.tryAcquire(5, TimeUnit.SECONDS);
                if (!locked) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "系统繁忙，请稍后再试", Toast.LENGTH_SHORT).show());
                    return;
                }

                MLFrame frame = MLFrame.fromBitmap(bmpCopy);
                long t0 = System.currentTimeMillis();
                @SuppressWarnings("unchecked")
                List<MLFaceTemplateResult> res = verifier.setTemplateFace(frame);
                long t1 = System.currentTimeMillis();

                if (res == null || res.isEmpty()) {
                    hasTemplate.set(false);
                    runOnUiThread(() -> Toast.makeText
                            (MainActivity.this, "模板设置失败：图像中未检测到人脸", Toast.LENGTH_LONG).show());
                    Log.w(TAG, "setTemplateFace returned empty/null (cost=" + (t1 - t0) + "ms)");
                } else {
                    hasTemplate.set(true);
                    Integer templateId = res.get(0).getTemplateId();

                    // 保存位图到临时文件交给 UI 以便用户输入 userId
                    String savedPath = null;
                    try {
                        savedPath = saveTemplateImageToFile(bmpCopy, "tmp_template_" + System.currentTimeMillis());
                        if (savedPath == null) {
                            Log.e(TAG, "Failed saving bmpCopy to file for UI handoff");
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to persist template image for UI", ex);
                    }

                    final String finalSavedPath = savedPath;
                    final Integer finalTemplateId = templateId;

                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "模板设置成功", Toast.LENGTH_SHORT).show();
                        showUserIdInputDialogWithPath(finalSavedPath, finalTemplateId);
                    });

                    StringBuilder sb = new StringBuilder();
                    sb.append("模板设置成功 (").append(res.size()).append(" faces) cost=").append(t1 - t0).append("ms");
                    for (MLFaceTemplateResult r : res) {
                        sb.append(" id=").append(r.getTemplateId());
                    }
                    Log.i(TAG, sb.toString());
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                hasTemplate.set(false);
                Log.e(TAG, "setTemplateFace error", t);
                final String errorMessage = t.getMessage();
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "模板设置异常: " + errorMessage, Toast.LENGTH_LONG).show());
            } finally {
                if (locked) {
                    try { hmsSemaphore.release(); } catch (Throwable ignored) {}
                }
                // 不在这里回收 bmpCopy，让保存流程或 GC 管理（如有需要可回收）
            }
    }


    private void showUserIdInputDialogWithPath(final String templatePath, final Integer templateId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("注册用户");

        final EditText input = new EditText(this);
        input.setHint("输入用户ID");
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String userId = input.getText().toString().trim();
            if (userId.isEmpty()) {
                Toast.makeText(MainActivity.this, "用户ID不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            templateExecutor.submit(() -> saveUserTemplateWithPath(userId, templatePath, templateId));
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void saveUserTemplateWithPath(String userId, String templatePath, Integer templateId) {
        try {
            if (templatePath == null) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "模板未持久化，注册失败", Toast.LENGTH_SHORT).show());
                return;
            }

            File tmpFile = new File(templatePath);
            File dir = new File(getFilesDir(), "face_templates");
            if (!dir.exists()) dir.mkdirs();
            File dest = new File(dir, userId + ".png");

            // 尝试重命名，否则拷贝
            if (!tmpFile.renameTo(dest)) {
                try (FileInputStream in = new FileInputStream(tmpFile);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                tmpFile.delete();
            }

            String finalPath = dest.getAbsolutePath();
            UserEntity user = new UserEntity(userId, userId, finalPath, templateId, System.currentTimeMillis());
            userDao.insert(user);

            if (templateId != null) templateIdToUserId.put(templateId, userId);

            runOnUiThread(() -> Toast.makeText(MainActivity.this, "用户 " + userId + " 注册成功", Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "保存用户模板失败", e);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "注册失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * 发送裁切的人脸给 HMS asyncAnalyseFrame 做异步比对
     * 使用 Semaphore 限制并发（避免堆积）
     */
    private void sendToHmsVerifier(Bitmap faceBitmap) {
        if (faceBitmap == null) return;

        if (!hasTemplate.get()) {
            Log.e(TAG, "sendToHmsVerifier: no template set, skip verify");
            updateResultText("未设置模板，请先注册模板");
            return;
        }

        if (verifier != null) {
            // 非阻塞尝试获取 hmsSemaphore，若失败则丢帧
            if (!hmsSemaphore.tryAcquire()) {
                Log.d(TAG, "HMS busy, skipping asyncAnalyseFrame");
                return;
            }

            final Bitmap toSend = faceBitmap.copy(Bitmap.Config.ARGB_8888, true);
            if (toSend == null) {
                Log.e(TAG, "Failed to copy bitmap for HMS verification");
                hmsSemaphore.release();
                return;
            }

            try {
                MLFrame frame = MLFrame.fromBitmap(toSend);
                verifier.asyncAnalyseFrame(frame)
                        .addOnSuccessListener(results -> {
                            try {
                                handleHmsSuccess(results);
                            } finally {
                                try { if (!toSend.isRecycled()) toSend.recycle(); } catch (Throwable ignored) {}
                                try { hmsSemaphore.release(); } catch (Throwable ignored) {}
                            }
                        })
                        .addOnFailureListener(e -> {
                            try {
                                handleHmsFailure(e);
                            } finally {
                                try { if (!toSend.isRecycled()) toSend.recycle(); } catch (Throwable ignored) {}
                                try { hmsSemaphore.release(); } catch (Throwable ignored) {}
                            }
                        });
            } catch (Throwable t) {
                Log.e(TAG, "sendToHmsVerifier exception", t);
                try { if (!toSend.isRecycled()) toSend.recycle(); } catch (Throwable ignored) {}
                try { hmsSemaphore.release(); } catch (Throwable ignored) {}
            }
            return;
        }

        // verifier == null: 本地降级比对（后台线程）
        templateExecutor.submit(() -> {
            try {
                Pair<Integer, Float> best = localVerify(faceBitmap);
                if (best == null) {
                    updateResultText("本地比对：未找到模板");
                } else {
                    int matchedId = best.first;
                    float score = best.second;
                    String userId = templateIdToUserId.get(matchedId);
                    String msg = String.format("本地 score: %.3f", score);
                    if (score >= 0.70f) {
                        if (userId != null) {
                            msg += " -> 匹配用户: " + userId;
                            onMatchUser(userId, score);
                        } else {
                            msg += " -> 匹配到本地模板 id=" + matchedId;
                        }
                    } else {
                        msg += " -> 不匹配";
                    }
                    updateResultText(msg);
                }
            } catch (Throwable t) {
                Log.e(TAG, "localVerify failed", t);
                updateResultText("本地比对异常");
            }
        });
    }


    // 返回 Pair<matchedTemplateId, score>，score in [0..1]，越大越像。若没有模板则返回 null。
    private Pair<Integer, Float> localVerify(Bitmap probe) {
        if (probe == null) return null;
        // 先准备 probe 缩放灰度
        Bitmap probeSmall = Bitmap.createScaledBitmap(probe, 64, 64, true);
        float[] probeGray = bitmapToGrayArray(probeSmall);
        try {
            Integer bestId = null;
            float bestScore = -1f;
            // 遍历所有本地模板（templateIdToUserId 中包含本地与 HMS）
            for (Map.Entry<Integer, String> e : templateIdToUserId.entrySet()) {
                int tid = e.getKey();
                Bitmap tpl = localTemplateCache.get(tid);
                if (tpl == null) {
                    // 若未缓存，尝试从 DB 路径加载
                    try {
                        UserEntity ue = userDao.findByTemplateId(tid); // 你需要在 UserDao 加上此查询
                        if (ue != null && ue.templatePath != null) {
                            Bitmap loaded = BitmapFactory.decodeFile(ue.templatePath);
                            if (loaded != null) {
                                // 缓存以加速后续比对（或直接释放根据内存）
                                Bitmap scaled = Bitmap.createScaledBitmap(loaded, 64, 64, true);
                                localTemplateCache.put(tid, scaled);
                                tpl = scaled;
                                try { if (!loaded.isRecycled()) loaded.recycle(); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignore) {}
                }
                if (tpl == null) continue;
                float[] tplGray = bitmapToGrayArray(tpl);
                float score = compareGrayArraysSimilarity(probeGray, tplGray);
                if (score > bestScore) {
                    bestScore = score;
                    bestId = tid;
                }
            }
            if (bestId == null) return null;
            return new Pair<>(bestId, bestScore);
        } finally {
            try { if (!probeSmall.isRecycled()) probeSmall.recycle(); } catch (Throwable ignored) {}
        }
    }

    private float[] bitmapToGrayArray(Bitmap bmp) {
        // bmp assumed 64x64 ARGB
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w*h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        float[] gray = new float[w*h];
        for (int i=0;i<px.length;i++) {
            int c = px[i];
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            gray[i] = (0.299f*r + 0.587f*g + 0.114f*b) / 255f;
        }
        return gray;
    }

    // 简单相似度：1 - (MAE)，范围大致在 0..1
    private float compareGrayArraysSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        double sumAbs = 0.0;
        for (int i=0;i<a.length;i++) sumAbs += Math.abs(a[i] - b[i]);
        double mae = sumAbs / a.length; // 在 [0,1]
        float score = (float)(1.0 - mae); // 1.0 极相似，0.0 极不相似
        if (score < 0f) score = 0f;
        return score;
    }

    /**
     * 处理 HMS 成功回调：兼容不同 SDK（getScore / getSimilarity）并更新 UI
     */
    private void handleHmsSuccess(List<MLFaceVerificationResult> results) {
        if (results == null || results.isEmpty()) {
            updateResultText("HMS: 无比对结果");
            return;
        }

        // 这里只取第一个结果作为主判定（根据需要可遍历）
        MLFaceVerificationResult r = results.get(0);

        Integer templateId = null;
        try {
            // 尝试用反射获取 templateId
            Method mTemplateId = r.getClass().getMethod("getTemplateId");
            Object v = mTemplateId.invoke(r);
            if (v instanceof Integer) {
                templateId = (Integer) v;
            }
        } catch (Exception e) {
            Log.w(TAG, "getTemplateId reflection failed", e);
        }

        // 老版 vs 新版字段名不同：尝试用反射获取 score 或 similarity
        float score = Float.NaN;
        try {
            // 尝试 getScore()
            Method mScore = r.getClass().getMethod("getScore");
            Object v = mScore.invoke(r);
            if (v instanceof Number) score = ((Number) v).floatValue();
        } catch (NoSuchMethodException ignored) {
            // try getSimilarity()
            try {
                Method mSim = r.getClass().getMethod("getSimilarity");
                Object v2 = mSim.invoke(r);
                if (v2 instanceof Number) score = ((Number) v2).floatValue();
            } catch (Exception ex2) {
                // ignore
            }
        } catch (Exception e) {
            Log.w(TAG, "getScore reflection failed", e);
        }

        // If still NaN, try alternative via reflection over unknown class (defensive)
        if (Float.isNaN(score)) {
            try {
                Method[] ms = r.getClass().getDeclaredMethods();
                for (Method m : ms) {
                    if (m.getName().toLowerCase().contains("score") || m.getName().toLowerCase().contains("similar")) {
                        Object v = m.invoke(r);
                        if (v instanceof Number) {
                            score = ((Number) v).floatValue();
                            break;
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }

        String msg;
        if (!Float.isNaN(score)) {

            // 根据 templateId 查找用户
            String userId = null;
            if (templateId != null) {
                userId = templateIdToUserId.get(templateId);
            }

            msg = String.format("HMS score: %.3f", score);
            // 阈值：示例用 0.7，按你需求调节
            if (score >= 0.70f) {
                if (userId != null) {
                    msg += " -> 识别通过" + userId;
                    onMatchUser(userId, score);

                    // 2) 启动展示主界面的 Activity 并传递用户名（matchedUserName）
                    // 注意：将 TargetActivity.class 替换为实际使用 activity_main.xml 的 Activity 类名
                    Intent intent = new Intent(/* 当前 Activity 的上下文：*/ MainActivity.this, HomeActivity.class);
                    intent.putExtra("matchedUserName", userId);
                    intent.putExtra("matchedScore", score);
                    // 如果希望把已有的主界面实例唤到前台并触发 onNewIntent()，使用下面的 flags
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);

                    // 3) 结束当前人脸识别页（通常希望跳走识别页）
                    finish();

                } else {
                    msg += " -> 识别通过但未找到对应用户 (templateId: " + templateId + ")";
                }
            } else {
                msg += " -> 不匹配";
                if (userId != null) {
                    msg += " (用户: " + userId + " 分数不足)";
                }
            }
        // 记录日志用于调试
            Log.d(TAG, "识别结果 - templateId: " + templateId + ", userId: " + userId + ", score: " + score);
        } else {
            msg = "HMS: 比对成功但无法读取 score 字段";
        }

        updateResultText(msg);

    }

    private void onMatchUser(String userId, float score) {
        Log.i(TAG, "用户匹配: " + userId + ", 分数: " + score);

    }


    private void handleHmsFailure(Exception e) {
        String msg = "HMS 比对失败";
        try {
            // 如果是 MLException，可以读到 errCode
            Class<?> mlExCls = Class.forName("com.huawei.hms.mlsdk.common.MLException");
            if (mlExCls.isInstance(e)) {
                Method mGetErr = mlExCls.getMethod("getErrCode");
                Object code = mGetErr.invoke(e);
                msg = "HMS 比对失败 code=" + code;
            } else {
                msg = "HMS 比对异常: " + e.getMessage();
            }
        } catch (Throwable t) {
            msg = "HMS 比对异常: " + e.getMessage();
        }
        Log.e(TAG, "HMS failure", e);
        updateResultText(msg);
    }

    @MainThread
    private void updateResultText(String s) {
        runOnUiThread(() -> resultTextView.setText(s));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            ImageUtil.destroy();
        } catch (Throwable ignored) {
        }

        // 新增：关闭 templateExecutor（模板设置相关线程池）
        try {
            if (templateExecutor != null) {
                templateExecutor.shutdownNow(); // 强制关闭所有任务
            }
        } catch (Throwable ignored) {
        }

        // 关闭 CameraX 相关线程 / analyzer（原有逻辑保留）
        try {
            if (cameraExecutor != null) {
                cameraExecutor.shutdownNow();
                cameraExecutor = null;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (cameraAnalyzer != null) {
                cameraAnalyzer.shutdown();
                cameraAnalyzer = null;
            }
        } catch (Throwable ignored) {
        }

        // 释放 HMS verifier（原有逻辑保留）
        try {
            if (verifier != null) {
                try {
                    Method mStop = verifier.getClass().getMethod("stop");
                    mStop.invoke(verifier);
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "verifier stop failed", t);
        }

        try {
            if (lastDetectedFace != null && !lastDetectedFace.isRecycled()) {
                lastDetectedFace.recycle();
                lastDetectedFace = null;
            }
        } catch (Throwable ignored) {
        }
    }
}