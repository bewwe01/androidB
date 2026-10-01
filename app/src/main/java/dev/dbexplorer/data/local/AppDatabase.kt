package dev.dbexplorer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ConnectionProfileEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
}
