package com.voiceaccounting.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "counterparties",
    indices = [Index(value = ["name"], unique = true)]
)
data class Counterparty(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null
)
