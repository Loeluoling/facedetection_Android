package com.example.facedemo;


import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(UserEntity user);

    @Update
    void update(UserEntity user);

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    UserEntity findById(String userId);

    @Query("SELECT * FROM users")
    List<UserEntity> getAll();

    @Query("SELECT * FROM users WHERE templateId = :templateId LIMIT 1")
    UserEntity findByTemplateId(int templateId);

}