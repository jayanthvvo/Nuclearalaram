package com.example.nuclearalarm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update
@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms")
    fun getAllAlarms(): List<AlarmEntity>

    @Insert
    fun insertAlarm(alarm: AlarmEntity): Long // Returns the newly generated ID

    @Delete
    fun deleteAlarm(alarm: AlarmEntity)
    @Update
    fun updateAlarm(alarm: AlarmEntity)
}