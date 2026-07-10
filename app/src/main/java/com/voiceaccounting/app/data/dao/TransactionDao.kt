package com.voiceaccounting.app.data.dao

import androidx.room.*
import com.voiceaccounting.app.data.model.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactionsJournal(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions WHERE counterparty_id = :counterpartyId")
    fun getTransactionsForCounterparty(counterpartyId: Long): Flow<List<Transaction>>
}
