package com.example.facedemo;


import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {
    private TextView tvUserId;
    private Button btnStore;
    private Button btnRetrieve;
    private Button btnAdmin;

    // 保存当前显示的用户名（可能为 null）
    private String currentUser = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvUserId = findViewById(R.id.tv_user_id);
        btnStore = findViewById(R.id.btn_store);
        btnRetrieve = findViewById(R.id.btn_retrieve);
        btnAdmin = findViewById(R.id.btn_admin);

        // 处理首次启动传入的 intent（来自人脸识别页）
        handleIntent(getIntent());

        // 点击“存物”——跳转到 CabinetActivity，并传 userId 与 mode
        btnStore.setOnClickListener(v -> {
            Intent i = new Intent(HomeActivity.this, CabinetActivity.class);
            i.putExtra("userId", currentUser);
            i.putExtra("mode", "存物");
            startActivity(i);
        });

        // 点击“取物”——跳转到 CabinetActivity，并传 userId 与 mode
        btnRetrieve.setOnClickListener(v -> {
            Intent i = new Intent(HomeActivity.this, CabinetActivity.class);
            i.putExtra("userId", currentUser);
            i.putExtra("mode", "取物");
            startActivity(i);
        });

        // 管理员入口保持原逻辑（如果暂时不用可以不处理）
        btnAdmin.setOnClickListener(v -> {
            // TODO: 管理员入口逻辑（跳转到登录页）
            // startActivity(new Intent(HomeActivity.this, AdminLoginActivity.class));
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // 当已有 HomeActivity 实例时，从识别页唤回会走这里
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            // 尝试从 SharedPreferences 读取上次值（如果存在）
            SharedPreferences sp = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String last = sp.getString("last_matched_user", null);
            if (last != null) {
                currentUser = last;
                tvUserId.setText("用户ID: " + currentUser);
            } else {
                tvUserId.setText("用户ID: 未识别");
            }
            return;
        }

        // 优先读取 intent 里传过来的用户名
        String matched = intent.getStringExtra("matchedUserName");
        if (matched != null && !matched.isEmpty()) {
            currentUser = matched;
            tvUserId.setText("用户ID: " + currentUser);

            // 可选：持久化最近一次识别到的用户，方便重启或没有 intent 时回填
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("last_matched_user", currentUser)
                    .apply();
        } else {
            // 回退到 SharedPreferences
            SharedPreferences sp = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String last = sp.getString("last_matched_user", null);
            if (last != null) {
                currentUser = last;
                tvUserId.setText("用户ID: " + currentUser);
            } else {
                currentUser = null;
                tvUserId.setText("用户ID: 未识别");
            }
        }
    }
}
