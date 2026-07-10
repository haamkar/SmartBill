package com.voiceaccounting.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.voiceaccounting.app.data.dao.CounterpartyDao
import com.voiceaccounting.app.data.dao.TransactionDao
import com.voiceaccounting.app.data.model.Counterparty
import com.voiceaccounting.app.data.model.Transaction

@Database(entities = [Counterparty::class, Transaction::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun counterpartyDao(): CounterpartyDao
    abstract fun transactionDao(): TransactionDao
}
