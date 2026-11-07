package dev.quietube.account

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.CookieJar
import okhttp3.OkHttpClient

@Module @InstallIn(SingletonComponent::class)
object AccountNetworkModule {
    @Provides @Singleton @AccountHttp fun client(): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val url = chain.request().url
            if (!allowedAccountUrl(url.scheme, url.host, url.encodedPath)) throw IOException("Account request destination blocked.")
            chain.proceed(chain.request())
        }.build()
}
internal fun allowedAccountUrl(scheme: String, host: String, path: String): Boolean =
    scheme == "https" && host == "www.youtube.com" && (path == "/" || path.startsWith("/youtubei/v1/"))
