package com.voiceaccounting.app.data.dao

import androidx.room.*
import com.voiceaccounting.app.data.model.Counterparty
import kotlinx.coroutines.flow.Flow

@Dao
interface CounterpartyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(counterparty: Counterparty): Long

    @Query("SELECT * FROM counterparties WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Counterparty?

    @Query("SELECT * FROM counterparties")
    fun getAllCounterparties(): Flow<List<Counterparty>>
}
