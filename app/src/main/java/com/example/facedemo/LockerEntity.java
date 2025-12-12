package com.example.facedemo;


import androidx.room.Entity;
import androidx.room.PrimaryKey;

// 管理柜子状态
@Entity(tableName = "lockers")
public class LockerEntity {
    public static final int STATUS_FREE = 0;
    public static final int STATUS_OCCUPIED = 1;
    @PrimaryKey
    public int lockerId;         // 1,2,3 ...
    public String ownerUserId;   // userId 或 null
    public int status;           // 0 = FREE, 1 = OCCUPIED

    public LockerEntity(int lockerId, String ownerUserId, int status) {
        this.lockerId = lockerId;
        this.ownerUserId = ownerUserId;
        this.status = status;
    }
}