// com/kaanyildiz/videoinspectorapp/data/remote/NetworkModule.kt
package com.kaanyildiz.videoinspectorapp.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import kotlinx.coroutines.runBlocking

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // Token’ı header’a koyan interceptor
    @Provides @Singleton
    fun provideAuthInterceptor(tokenStore: TokenStore): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val token = runBlocking { tokenStore.token() } // token() muhtemelen suspend
        val req = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else original
        chain.proceed(req)
    }

    @Provides @Singleton
    fun provideOkHttp(authInterceptor: Interceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // İstersen debug’da BODY, release’de BASIC kullan
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)   // <-- eklendi
            .addInterceptor(logging)
            .build()
    }

    @Provides @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            // EMÜLATÖR: http://10.0.2.2:3000/
            // GERÇEK CİHAZ (aynı Wi-Fi): PC’nin LAN IP’si, örn: http://192.168.1.136:3000/
            .baseUrl("http://192.168.1.136:3000/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides @Singleton
    fun provideApi(retrofit: Retrofit): ApiService =
        retrofit.create(ApiService::class.java)
}
