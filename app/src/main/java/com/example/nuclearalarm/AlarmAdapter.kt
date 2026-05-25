package com.example.nuclearalarm

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AlarmAdapter(
    private var alarms: List<AlarmEntity>,
    private val onToggleAlarm: (AlarmEntity, Boolean) -> Unit // Callback for when the switch is flipped
) : RecyclerView.Adapter<AlarmAdapter.AlarmViewHolder>() {

    // This class holds the views from item_alarm.xml
    class AlarmViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeText: TextView = view.findViewById(R.id.alarmTimeText)
        val alarmSwitch: Switch = view.findViewById(R.id.alarmSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return AlarmViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        val alarm = alarms[position]

        // Format the time correctly (e.g., "08:05")
        holder.timeText.text = String.format("%02d:%02d", alarm.hour, alarm.minute)

        // Remove listener temporarily so we don't accidentally trigger it while setting up
        holder.alarmSwitch.setOnCheckedChangeListener(null)
        holder.alarmSwitch.isChecked = alarm.isEnabled

        // Listen for when the user flips the switch
        holder.alarmSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggleAlarm(alarm, isChecked)
        }
    }

    override fun getItemCount() = alarms.size

    // Call this from MainActivity when the database changes
    fun updateAlarms(newAlarms: List<AlarmEntity>) {
        alarms = newAlarms
        notifyDataSetChanged()
    }
}