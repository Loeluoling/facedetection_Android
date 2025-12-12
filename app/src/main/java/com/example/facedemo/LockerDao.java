package com.example.facedemo;


import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface LockerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LockerEntity locker);

    @Update
    void update(LockerEntity locker);

    @Query("SELECT * FROM lockers WHERE lockerId = :lockerId LIMIT 1")
    LockerEntity findById(int lockerId);

    @Query("SELECT * FROM lockers")
    List<LockerEntity> getAll();

    @Query("SELECT * FROM lockers WHERE ownerUserId = :userId")
    List<LockerEntity> findByOwner(String userId);
}