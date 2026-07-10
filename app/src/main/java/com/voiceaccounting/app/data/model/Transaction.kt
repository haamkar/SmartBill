package com.voiceaccounting.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Counterparty::class,
            parentColumns = ["id"],
            childColumns = ["counterparty_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // BUY, SELL, RECEIVE_TOMAN, PAY_TOMAN
    @ColumnInfo(name = "counterparty_id") val counterpartyId: Long,
    val amount: Double,
    @ColumnInfo(name = "currency_type") val currencyType: String,
    @ColumnInfo(name = "exchange_rate") val exchangeRate: Double,
    @ColumnInfo(name = "toman_amount") val tomanAmount: Double,
    @ColumnInfo(name = "is_delivered") val isDelivered: Boolean,
    val timestamp: Long,
    @ColumnInfo(name = "audio_file_path") val audioFilePath: String? = null
)
