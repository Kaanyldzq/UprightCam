// app/src/main/java/com/kaanyildiz/videoinspectorapp/di/RepoModule.kt
package com.kaanyildiz.videoinspectorapp.di

import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.repository.VideoRepository
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepoModule {

    @Provides
    @Singleton
    fun provideVideoRepository(
        api: ApiService,
        tokenStore: TokenStore
    ): VideoRepository {
        return VideoRepository(api, tokenStore)
    }
}
