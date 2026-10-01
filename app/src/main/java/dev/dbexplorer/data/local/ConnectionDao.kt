package dev.dbexplorer.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connection_profile ORDER BY folder COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profile WHERE id = :id")
    suspend fun get(id: Long): ConnectionProfileEntity?

    @Insert
    suspend fun insert(entity: ConnectionProfileEntity): Long

    @Update
    suspend fun update(entity: ConnectionProfileEntity)

    @Query("DELETE FROM connection_profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE connection_profile SET lastUsedAt = :timestamp WHERE id = :id")
    suspend fun markUsed(id: Long, timestamp: Long)
}
