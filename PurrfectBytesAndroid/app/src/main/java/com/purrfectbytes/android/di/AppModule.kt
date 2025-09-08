package com.purrfectbytes.android.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.purrfectbytes.android.data.api.PurrfectBytesApi
import com.purrfectbytes.android.data.repository.AuthRepository
import com.purrfectbytes.android.data.repository.TTSRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun providePurrfectBytesApi(okHttpClient: OkHttpClient): PurrfectBytesApi {
        return Retrofit.Builder()
            .baseUrl("http://10.0.2.2:8000/") // For emulator, use actual IP for device
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PurrfectBytesApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }
    
    @Provides
    @Singleton
    fun provideAuthRepository(
        api: PurrfectBytesApi,
        dataStore: DataStore<Preferences>
    ): AuthRepository {
        return AuthRepository(api, dataStore)
    }
    
    @Provides
    @Singleton
    fun provideTTSRepository(
        api: PurrfectBytesApi,
        @ApplicationContext context: Context
    ): TTSRepository {
        return TTSRepository(api, context)
    }
}