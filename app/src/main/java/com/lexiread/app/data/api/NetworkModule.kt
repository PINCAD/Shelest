package com.lexiread.app.data.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkModule {

    private val gson = GsonBuilder()
        .setLenient()
        .serializeNulls()
        .create()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "ShelestApp/2.0 (android; educational)")
                .header("Accept-Language", "ru,en;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    // Base URL is a placeholder — all calls use @Url absolute URLs
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://ru.wikipedia.org/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val wikipediaApiService: WikipediaApiService =
        retrofit.create(WikipediaApiService::class.java)

    val dictionaryApiService: DictionaryApiService =
        retrofit.create(DictionaryApiService::class.java)

    val openLibraryApiService: OpenLibraryApiService =
        retrofit.create(OpenLibraryApiService::class.java)

    val wikidataApiService: WikidataApiService =
        retrofit.create(WikidataApiService::class.java)

    val googleBooksApiService: GoogleBooksApiService =
        retrofit.create(GoogleBooksApiService::class.java)

    val fantLabApiService: FantLabApiService =
        retrofit.create(FantLabApiService::class.java)

    // Personal Yandex.Dictionary API key (dictionary.yandex.net) — free tier, ru-ru/en-en lookups.
    val yandexDictionaryApiKey: String =
        com.lexiread.app.BuildConfig.YANDEX_DICTIONARY_API_KEY

    companion object {
        /**
         * Google Books refuses every anonymous request — `?q=test` included — with 429 and
         * `"quota_limit_value": "0"`, so the catalogue the app leans on hardest is the one that
         * cannot be used without a key. The key ships with the app because the alternative is
         * asking every reader to visit the Google Cloud console before the screen works at all.
         *
         * It must stay unrestricted by application: an Android-restricted key is checked against
         * `X-Android-Package` and `X-Android-Cert` headers, which plain OkHttp does not send, and
         * every request would come back rejected. The restriction that does hold here is the API
         * one — Books API only, with a daily quota.
         */
        val GOOGLE_BOOKS_API_KEY: String = com.lexiread.app.BuildConfig.GOOGLE_BOOKS_API_KEY
    }
}
