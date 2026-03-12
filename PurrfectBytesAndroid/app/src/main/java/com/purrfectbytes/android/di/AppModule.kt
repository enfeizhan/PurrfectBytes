package com.purrfectbytes.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextRecognitionProcessor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideTTSService(@ApplicationContext context: Context): TTSService {
        return TTSService(context)
    }

    @Provides
    @Singleton
    fun provideTextRecognitionProcessor(@ApplicationContext context: Context): TextRecognitionProcessor {
        return TextRecognitionProcessor(context)
    }

    @Provides
    @Singleton
    fun provideAnthropicService(): com.purrfectbytes.android.services.AnthropicService {
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        return retrofit.create(com.purrfectbytes.android.services.AnthropicService::class.java)
    }
}