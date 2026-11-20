package com.rehearsal.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observeById(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: String): TrackEntity?

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query(
        """
        UPDATE tracks SET
            originalBpm = :bpm,
            updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun updateBpm(id: String, bpm: Float, now: Long)

    @Query(
        """
        UPDATE tracks SET
            status = :status,
            errorMessage = :errorMessage,
            vocalsPath = :vocalsPath,
            drumsPath = :drumsPath,
            bassPath = :bassPath,
            otherPath = :otherPath,
            splitKind = :splitKind,
            updatedAtEpochMs = :now
        WHERE id = :id
        """,
    )
    suspend fun updateSplit(
        id: String,
        status: String,
        errorMessage: String?,
        vocalsPath: String?,
        drumsPath: String?,
        bassPath: String?,
        otherPath: String?,
        splitKind: String?,
        now: Long,
    )

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: String)
}
