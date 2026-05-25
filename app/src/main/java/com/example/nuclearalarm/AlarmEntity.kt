package com.example.nuclearalarm

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val daysOfWeek: String // Optional: e.g., "1,2,3,4,5" for Mon-Fri
)