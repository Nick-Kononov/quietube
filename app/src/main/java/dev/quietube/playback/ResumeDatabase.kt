package dev.quietube.playback

import android.content.Context
import androidx.room.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Entity(tableName = "resume_positions")
data class ResumePosition(@PrimaryKey val videoUrl: String, val positionMs: Long, val updatedAt: Long)
@Dao
interface ResumeDao {
    @Query("SELECT positionMs FROM resume_positions WHERE videoUrl = :url")
    suspend fun position(url: String): Long?
    @Query("DELETE FROM resume_positions") suspend fun clear()
    @Upsert suspend fun save(position: ResumePosition)
    @Query("DELETE FROM resume_positions WHERE videoUrl NOT IN (SELECT videoUrl FROM resume_positions ORDER BY updatedAt DESC LIMIT 1000)")
    suspend fun prune()
}
@Database(entities = [ResumePosition::class], version = 1, exportSchema = false)
abstract class ResumeDatabase : RoomDatabase() { abstract fun positions(): ResumeDao }
@Module @InstallIn(SingletonComponent::class)
object ResumeModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ResumeDatabase =
        Room.databaseBuilder(context, ResumeDatabase::class.java, "playback.db").build()
    @Provides fun dao(database: ResumeDatabase): ResumeDao = database.positions()
}

internal fun resumablePosition(position: Long, duration: Long): Long =
    if (position < 0 || (duration > 0 && position >= duration - 5_000)) 0 else position
