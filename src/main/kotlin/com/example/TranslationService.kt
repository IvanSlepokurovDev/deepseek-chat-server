package com.example

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private const val GOOGLE_TRANSLATE_URL = "https://clients5.google.com/translate_a/t"

/**
 * Бесплатный (неофициальный) endpoint Google Translate: client=dict-chrome-ex —
 * тот же, что использует расширение Google Translate для Chrome. Без API-ключа,
 * язык исходного текста определяется автоматически.
 * Бросает исключение при ошибке запроса, разбора или пустом результате.
 */
suspend fun translate(client: HttpClient, text: String, targetLang: String): String {
    val body = client.get(GOOGLE_TRANSLATE_URL) {
        header(HttpHeaders.UserAgent, "Mozilla/5.0")
        parameter("client", "dict-chrome-ex")
        parameter("sl", "auto")
        parameter("tl", targetLang)
        parameter("q", text)
    }.bodyAsText()

    // Ответ имеет вид [["перевод", "язык_оригинала"], ...] — склеиваем переводы всех сегментов
    val translation = Json.parseToJsonElement(body).jsonArray
        .mapNotNull { segment -> segment.jsonArray.firstOrNull()?.jsonPrimitive?.content }
        .joinToString("")
    check(translation.isNotBlank()) { "Google Translate вернул пустой результат: $body" }
    return translation
}
