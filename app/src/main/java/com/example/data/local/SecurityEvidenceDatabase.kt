package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "security_photos")
data class SecurityPhotoEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val filePath: String,
  val reason: String,
  val attemptCount: Int,
  val latitude: Double,
  val longitude: Double,
  val accuracyMeters: Double,
  val batteryPercent: Int,
  val capturedAtMillis: Long,
)

@Entity(tableName = "pending_alerts")
data class PendingAlertEntity(
  @PrimaryKey val id: String,
  val type: String,
  val title: String,
  val message: String,
  val latitude: Double,
  val longitude: Double,
  val accuracyMeters: Double,
  val batteryPercent: Int,
  val hasPhotoEvidence: Boolean,
  val occurredAtMillis: Long,
)

@Dao
interface SecurityEvidenceDao {
  @Query("SELECT * FROM security_photos ORDER BY capturedAtMillis DESC")
  fun observePhotos(): Flow<List<SecurityPhotoEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPhoto(photo: SecurityPhotoEntity): Long

  @Query("DELETE FROM security_photos WHERE id = :photoId")
  suspend fun deletePhotoById(photoId: Long)

  @Query("SELECT * FROM pending_alerts ORDER BY occurredAtMillis ASC")
  suspend fun getPendingAlerts(): List<PendingAlertEntity>

  @Query("SELECT COUNT(*) FROM pending_alerts")
  fun observePendingAlertCount(): Flow<Int>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPendingAlert(alert: PendingAlertEntity)

  @Query("DELETE FROM pending_alerts WHERE id = :alertId")
  suspend fun deletePendingAlert(alertId: String)
}

@Database(
  entities = [SecurityPhotoEntity::class, PendingAlertEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class SecurityEvidenceDatabase : RoomDatabase() {
  abstract fun evidenceDao(): SecurityEvidenceDao

  companion object {
    @Volatile private var INSTANCE: SecurityEvidenceDatabase? = null

    fun getInstance(context: Context): SecurityEvidenceDatabase {
      return INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                SecurityEvidenceDatabase::class.java,
                "terraguard_evidence.db",
              )
              .build()
              .also { INSTANCE = it }
        }
    }

    fun evidenceDirectory(context: Context): File {
      val dir = File(context.applicationContext.filesDir, "security_evidence")
      if (!dir.exists()) dir.mkdirs()
      return dir
    }
  }
}
