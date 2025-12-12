package com.example.facedemo; // 按你的包名调整

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class CabinetActivity extends AppCompatActivity {
    private TextView tvInfo;

    // Locker UI references
    private LinearLayout llLocker1;
    private TextView tvLocker1Title;
    private TextView tvLocker1Status;

    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler uiHandler = new Handler(Looper.getMainLooper());

    private AppDatabase db;
    private LockerDao lockerDao;

    private String userId;
    private String mode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cabinet_status);

        tvInfo = findViewById(R.id.tv_cabinet_info);

        // Locker view init (1号)
        llLocker1 = findViewById(R.id.ll_locker1);
        tvLocker1Title = findViewById(R.id.tv_locker1_title);
        tvLocker1Status = findViewById(R.id.tv_locker1_status);

        Intent intent = getIntent();
        userId = intent != null ? intent.getStringExtra("userId") : null;
        mode = intent != null ? intent.getStringExtra("mode") : null; // store/retrieve

        String display;
        if (userId == null || userId.isEmpty()) {
            display = "模式: " + (mode != null ? mode : "未知") + "\n用户: 未识别（请先识别人脸）";
        } else {
            display = "模式: " + (mode != null ? mode : "未知") + "\n用户: " + userId;
        }
        tvInfo.setText(display);

        // 初始化 DB 和 DAO
        db = AppDatabase.getInstance(this);
        lockerDao = db.lockerDao();

        // 绑定点击事件（先设为不可点击直到加载状态完成）
        llLocker1.setOnClickListener(v -> {
            // 根据状态判断是存物还是取物
            handleLockerClick(1);
        });

        // 加载并渲染所有柜子（从 DB）
        loadLockersFromDb();
    }

    private void loadLockersFromDb() {
        executor.submit(() -> {
            List<LockerEntity> lockers = lockerDao.getAll();
            // 如果 DB 中没建表/没初始化指定的 locker row，则你可以初始化默认数据
            if (lockers == null || lockers.isEmpty()) {
                // 示例：确保1号柜存在
                LockerEntity l1 = lockerDao.findById(1);
                if (l1 == null) {
                    l1 = new LockerEntity(1, null, LockerEntity.STATUS_FREE);
                    lockerDao.insert(l1);
                }
                lockers = lockerDao.getAll();
            }
            final Map<Integer, LockerEntity> map = new HashMap<>();
            for (LockerEntity le : lockers) map.put(le.lockerId, le);

            uiHandler.post(() -> {
                // 更新 1号柜显示（扩展多个柜子按相同模式处理）
                updateLockerView(1, map.get(1));
            });
        });
    }

    private void updateLockerView(int lockerId, LockerEntity le) {
        // 这里只示例1号柜，根据 lockerId 可分支更新对应控件
        if (lockerId != 1) return;

        if (le == null) {
            // 未知，显示空闲
            tvLocker1Title.setText("1号柜");
            tvLocker1Status.setText("空闲");
            llLocker1.setBackground(ContextCompat.getDrawable(this, R.drawable.idle_bg));
            tvLocker1Title.setTextColor(ContextCompat.getColor(this, android.R.color.black));
            tvLocker1Status.setTextColor(ContextCompat.getColor(this, R.color.green_500)); // 定义颜色资源
            llLocker1.setEnabled(true);
            return;
        }

        if (le.status == LockerEntity.STATUS_FREE) {
            tvLocker1Title.setText("1号柜");
            tvLocker1Status.setText("空闲");
            llLocker1.setBackground(ContextCompat.getDrawable(this, R.drawable.idle_bg));
            tvLocker1Title.setTextColor(ContextCompat.getColor(this, R.color.dark_text)); // #1B5E20
            tvLocker1Status.setTextColor(ContextCompat.getColor(this, R.color.green_500)); // #4CAF50
            llLocker1.setEnabled(true);
        } else {
            tvLocker1Title.setText("1号柜");
            tvLocker1Status.setText("占用");
            llLocker1.setBackground(ContextCompat.getDrawable(this, R.drawable.occupied_bg));
            tvLocker1Title.setTextColor(ContextCompat.getColor(this, R.color.red_dark)); // #B71C1C
            tvLocker1Status.setTextColor(ContextCompat.getColor(this, R.color.red_500)); // #F44336

            // 若占用，判断当前用户是否为所有者
            if (userId != null && userId.equals(le.ownerUserId)) {
                // 自己占用：可取物（允许点击）
                llLocker1.setEnabled(true);
            } else {
                // 他人占用：禁用点击或显示提示
                llLocker1.setEnabled(false);
            }
        }
    }

    /**
     * 点击柜子后的逻辑：如果空闲则执行存物（在 openLocker 成功后写 DB 为占用）
     * 如果占用且为当前用户则允许取物（可在取物成功后清空 DB）
     */
    private void handleLockerClick(int lockerId) {
        executor.submit(() -> {
            LockerEntity le = lockerDao.findById(lockerId);
            if (le == null || le.status == LockerEntity.STATUS_FREE) {
                // 空闲 -> 发起开柜（存物）
                uiHandler.post(() -> Toast.makeText(this, "正在打开柜子，请放入物品并关闭", Toast.LENGTH_SHORT).show());
                // openLocker 在 UI 线程中会启动网络/IO线程并处理结果；这里直接调用 openLocker UI 方法
                uiHandler.post(() -> openLocker(lockerId));
            } else {
                // 占用
                if (userId != null && userId.equals(le.ownerUserId)) {
                    // 自己占用 -> 取物流程
                    uiHandler.post(() -> {
                        Toast.makeText(this, "检测到您是占用者，正在打开柜子...", Toast.LENGTH_SHORT).show();
                        openLocker(lockerId); // 取物也直接开柜，取完再释放 locker
                    });
                } else {
                    uiHandler.post(() -> Toast.makeText(this, "该柜子已被占用，非本人不可操作", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /**
     * 在你已有的 openLocker 方法里：当 lockerd 返回 OK 后要把 DB 更新为占用（或释放）
     * 下面是假设 openLocker 的 callback 成功后调用的更新逻辑。
     */
    private void markLockerOccupiedInDb(int lockerId, String ownerUserId) {
        executor.submit(() -> {
            LockerEntity locker = lockerDao.findById(lockerId);
            if (locker == null) {
                locker = new LockerEntity(lockerId, ownerUserId, LockerEntity.STATUS_OCCUPIED);
                lockerDao.insert(locker);
            } else {
                locker.ownerUserId = ownerUserId;
                locker.status = LockerEntity.STATUS_OCCUPIED;
                lockerDao.update(locker);
            }
            // 加载最新并刷新 UI
            LockerEntity updated = lockerDao.findById(lockerId);
            uiHandler.post(() -> updateLockerView(lockerId, updated));
        });
    }

    private void markLockerFreeInDb(int lockerId) {
        executor.submit(() -> {
            LockerEntity locker = lockerDao.findById(lockerId);
            if (locker != null) {
                locker.ownerUserId = null;
                locker.status = LockerEntity.STATUS_FREE;
                lockerDao.update(locker);
            }
            LockerEntity updated = lockerDao.findById(lockerId);
            uiHandler.post(() -> updateLockerView(lockerId, updated));
        });
    }

    /**
     * 打开 lockerId 对应的柜子（通过本地 lockerd）。
     * 成功后根据当前 mode 更新 DB（store -> 占用； retrieve -> 释放）。
     */
    private void openLocker(int lockerId) {
        // 禁用点击以防重复
        uiHandler.post(() -> {
            llLocker1.setEnabled(false);
            tvLocker1Status.setText("请求中...");
        });

        executor.submit(() -> {
            String cmdWithId = "OPEN " + lockerId + "\n";
            String cmdSimple = "OPEN\n";
            String resp = null;
            Exception error = null;

            // 先尝试发送带 id 的命令（更语义化）
            try {
                resp = sendCommandToLockerd(cmdWithId);
            } catch (Exception e) {
                Log.w(TAG, "send with id failed, will try simple OPEN", e);
                error = e;
                try {
                    // 后备：发送简单命令
                    resp = sendCommandToLockerd(cmdSimple);
                    error = null; // 后备成功则清除错误
                } catch (Exception e2) {
                    Log.e(TAG, "fallback simple OPEN failed", e2);
                    error = e2;
                    resp = null;
                }
            }

            // 额外保险：如果 resp 为空或者包含 UNKNOWN，尝试再发一次简单 OPEN（最后手段）
            if ((resp == null || resp.toUpperCase().contains("UNKNOWN") || !resp.toUpperCase().contains("OK")) && error == null) {
                try {
                    Log.d(TAG, "fallback: try simple OPEN (no id) because resp='" + resp + "'");
                    String resp2 = sendCommandToLockerd("OPEN\n");
                    if (resp2 != null) resp = resp2;
                } catch (Exception e2) {
                    Log.e(TAG, "final fallback OPEN failed", e2);
                    error = e2;
                }
            }

            final String finalRespRaw = resp;
            final Exception finalError = error;

            uiHandler.post(() -> {
                // 恢复点击
                llLocker1.setEnabled(true);

                if (finalError != null) {
                    tvLocker1Status.setText("打开失败");
                    Toast.makeText(CabinetActivity.this, "打开柜子失败: " + finalError.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e(TAG, "openLocker error", finalError);
                    return;
                }

                String finalResp = finalRespRaw != null ? finalRespRaw.trim() : null;
                Log.d(TAG, "openLocker: finalResp=[" + finalResp + "], mode=[" + mode + "], userId=[" + userId + "]");

                if (finalResp != null && finalResp.toUpperCase().contains("OK")) {
                    // 成功开柜：更新状态显示为已打开
                    tvLocker1Status.setText("已打开");

                    // 在 UI 线程安全地显示确认对话框（并检查 Activity 状态）
                    if (isFinishing() || isDestroyed()) {
                        Log.w(TAG, "Activity not available to show dialog (finishing/destroyed).");
                        return;
                    }

                    if ("store".equals(mode) || "存物".equals(mode)) {
                        // 存物：弹窗确认，再标记占用
                        new androidx.appcompat.app.AlertDialog.Builder(CabinetActivity.this)
                                .setTitle("请放入物品")
                                .setMessage("柜门已打开。请放入物品并关好柜门后，再点击“确认存物”。")
                                .setPositiveButton("确认存物", (dialog, which) -> {
                                    // 用户确认后，才更新数据库状态为“占用”
                                    markLockerOccupiedInDb(lockerId, userId);
                                      Toast.makeText(CabinetActivity.this, "存物完成，柜子已锁定。", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("取消", (dialog, which) -> {
                                    Toast.makeText(CabinetActivity.this, "已取消存物。", Toast.LENGTH_SHORT).show();
                                })
                                .setCancelable(false)
                                .show();
                    } else if ("retrieve".equals(mode) || "取物".equals(mode)) {
                        // 取物：开门后立即释放（自动化）
                        markLockerFreeInDb(lockerId);
                        Toast.makeText(CabinetActivity.this, "柜子已打开（自动释放）。", Toast.LENGTH_SHORT).show();
                    } else {
                        // 未指定 mode，默认标占用（保守策略也可以弹窗）
                        markLockerOccupiedInDb(lockerId, userId);
                    }
                } else {
                    // 非 OK 响应，显示并提示
                    tvLocker1Status.setText("响应: " + (finalResp == null ? "(无响应)" : finalResp));
                    Toast.makeText(CabinetActivity.this, "意外响应: " + (finalResp == null ? "(无响应)" : finalResp), Toast.LENGTH_LONG).show();
                }
            });
        });
    }


    /**
     * 低级网络函数：连接本地 lockerd 发送命令并返回响应（或抛异常）
     */
// 在 CabinetActivity 类中添加或替换此方法
    private static final String TAG = "CabinetActivity";

    private String sendCommandToLockerd(String command) throws Exception {
        Log.d(TAG, "sendCommandToLockerd: preparing to connect to 127.0.0.1:9090, command=" + command.replace("\n", "\\n"));

        // 这里使用 Socket 直接连接回环地址
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            Log.d(TAG, "socket.connect start");
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9090), 2000); // 2s connect timeout
            Log.d(TAG, "socket.connect done");
            socket.setSoTimeout(3000); // 3s read timeout

            java.io.OutputStream os = socket.getOutputStream();
            java.io.InputStream is = socket.getInputStream();

            Log.d(TAG, "writing command to lockerd: " + command.replace("\n", "\\n"));
            os.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();

            byte[] buf = new byte[256];
            int n = is.read(buf); // may throw SocketTimeoutException
            Log.d(TAG, "read returned n=" + n);
            if (n <= 0) {
                Log.w(TAG, "sendCommandToLockerd: read returned <=0, treating as empty response");
                return null;
            }
            String resp = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            Log.d(TAG, "response from lockerd: " + resp.replace("\n", "\\n"));
            return resp;
        } catch (Exception e) {
            Log.e(TAG, "sendCommandToLockerd failed", e);
            throw e;
        } finally {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}

/**
 * 你的 openLocker(1) 方法（network 调用 lockerd）在成功后需要调用 markLockerOccupiedInDb()
 * 例如：在 sendCommandToLockerd 返回且响应包含 "OK" 后执行：
 *
 * if (finalResp != null && finalResp.toUpperCase().contains("OK")) {
 *     // 存物流程：把柜子标为占用
 *     markLockerOccupiedInDb(lockerId, userId);
 * }
 *
 * 如果是取物并且取物成功，可以调用 markLockerFreeInDb(lockerId);
 */