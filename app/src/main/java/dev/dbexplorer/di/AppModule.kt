package dev.dbexplorer.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.dbexplorer.data.local.AppDatabase
import dev.dbexplorer.data.local.ConnectionDao
import dev.dbexplorer.data.secrets.KeystoreSecretStore
import dev.dbexplorer.data.secrets.SecretStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "dbexplorer.db").build()

    @Provides
    fun connectionDao(db: AppDatabase): ConnectionDao = db.connectionDao()

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = KeystoreSecretStore(context)
}
