package com.lexiread.app.data.api

import com.lexiread.app.data.model.DictionaryApiEntry
import com.lexiread.app.data.model.YandexLookupResponse
import retrofit2.http.GET
import retrofit2.http.Url

/** Yandex.Dictionary + dictionaryapi.dev — real lexical sources, exact-form lookup only. */
interface DictionaryApiService {

    @GET
    suspend fun yandexLookup(@Url url: String): YandexLookupResponse

    @GET
    suspend fun dictionaryApiDev(@Url url: String): List<DictionaryApiEntry>
}
