package com.habitminer.di

import android.content.Context
import com.habitminer.data.AppDatabase
import com.habitminer.data.AppUsageDao
import com.habitminer.data.BaselineDao
import com.habitminer.data.ContextDao
import com.habitminer.data.DeviationDao
import com.habitminer.data.DeviceEventDao
import com.habitminer.data.HabitDao
import com.habitminer.data.LabelDao
import com.habitminer.data.PlaceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    fun provideAppUsageDao(database: AppDatabase): AppUsageDao = database.appUsageDao()

    @Provides
    fun provideContextDao(database: AppDatabase): ContextDao = database.contextDao()

    @Provides
    fun provideHabitDao(database: AppDatabase): HabitDao = database.habitDao()

    @Provides
    fun provideBaselineDao(database: AppDatabase): BaselineDao = database.baselineDao()

    @Provides
    fun provideDeviationDao(database: AppDatabase): DeviationDao = database.deviationDao()

    @Provides
    fun provideDeviceEventDao(database: AppDatabase): DeviceEventDao = database.deviceEventDao()

    @Provides
    fun provideLabelDao(database: AppDatabase): LabelDao = database.labelDao()

    @Provides
    fun providePlaceDao(database: AppDatabase): PlaceDao = database.placeDao()

    @Provides
    fun provideBatchDao(database: AppDatabase): com.habitminer.data.BatchDao = database.batchDao()
}
