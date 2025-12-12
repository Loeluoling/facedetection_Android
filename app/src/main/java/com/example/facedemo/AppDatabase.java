package com.example.facedemo;


import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

// version 如果以后 schema 变更要 +1 并提供 Migration
@Database(entities = {UserEntity.class, LockerEntity.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static final String DB_NAME = "facedemo_db";
    private static volatile AppDatabase INSTANCE;

    public abstract UserDao userDao();
    public abstract LockerDao lockerDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, DB_NAME)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}