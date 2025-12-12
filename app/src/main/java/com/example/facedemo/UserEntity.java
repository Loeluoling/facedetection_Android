package com.example.facedemo;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

// 存储用户与模板的元信息
@Entity(tableName = "users")
public class UserEntity {
    @PrimaryKey
    @NonNull
    public String userId;          // 例如 "alice", "bob"（外部输入唯一标识）
    public String name;           // 可选显示名
    public String templatePath;   // 本地文件路径，例如 files/face_templates/{userId}.png
    public Integer templateId;    // 如果 HMS SDK 返回 templateId，可记录（可为 null）
    public long createdAt;

    public UserEntity(@NonNull String userId, String name, String templatePath, Integer templateId, long createdAt) {
        this.userId = userId;
        this.name = name;
        this.templatePath = templatePath;
        this.templateId = templateId;
        this.createdAt = createdAt;
    }
}
