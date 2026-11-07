package dev.quietube.di

import dev.quietube.data.AnonymousSource
import dev.quietube.data.DefaultVideoRepository
import dev.quietube.data.NewPipeAnonymousSource
import dev.quietube.data.VideoRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun account(impl: dev.quietube.account.InnerTubeAccountSource): dev.quietube.data.AccountSource
    @Binds @Singleton abstract fun anonymous(impl: NewPipeAnonymousSource): AnonymousSource
    @Binds @Singleton abstract fun repository(impl: DefaultVideoRepository): VideoRepository
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton fun anonymousHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
}
