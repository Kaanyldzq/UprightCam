// app/src/main/java/com/kaanyildiz/videoinspectorapp/di/TokenModule.kt
package com.kaanyildiz.videoinspectorapp.di

import com.kaanyildiz.videoinspectorapp.data.local.TokenStoreImpl
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TokenModule {
    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: TokenStoreImpl): TokenStore
}
