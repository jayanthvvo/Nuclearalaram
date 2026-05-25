package com.example.nuclearalarm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms")
    fun getAllAlarms(): List<AlarmEntity>

    @Insert
    fun insertAlarm(alarm: AlarmEntity): Long // Returns the newly generated ID

    @Delete
    fun deleteAlarm(alarm: AlarmEntity)
}