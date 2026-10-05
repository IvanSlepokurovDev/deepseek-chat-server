package com.example

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import com.typesafe.config.ConfigFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Collections

private const val PORT = 8080
private const val DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
private const val MODEL = "deepseek-flash"
// Диалог с DeepSeek ведётся на английском, поэтому системный промпт тоже на английском
private const val SYSTEM_PROMPT = "You are a helpful assistant"

@Serializable
data class ChatRequest(val prompt: String)

@Serializable
data class ChatResponse(val reply: String)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class DeepSeekRequest(
    val model: String = MODEL,
    val messages: List<Message>,
    val thinking: Thinking = Thinking(),
    @SerialName("reasoning_effort") val reasoningEffort: String = "high",
    val stream: Boolean = false,
)

@Serializable
data class Thinking(val type: String = "enabled")

@Serializable
data class DeepSeekResponse(val choices: List<Choice> = emptyList())

@Serializable
data class Choice(val message: Message)

fun main() {
    // Ключ берём из local.conf (в git не попадает); fallback — application.conf из ресурсов,
    // где значение может прийти из переменной окружения DEEPSEEK_API_KEY
    val localFile = File("local.conf")
    val config = if (localFile.exists()) {
        ConfigFactory.parseFile(localFile).withFallback(ConfigFactory.load())
    } else {
        ConfigFactory.load()
    }

    val apiKey = config.getString("deepseek.apiKey").takeIf { it.isNotBlank() }
        ?: error("Не задан ключ DeepSeek API: заполните deepseek.apiKey в local.conf или задайте DEEPSEEK_API_KEY")

    // История диалога хранится в памяти и сбрасывается при перезапуске сервера
    val history = Collections.synchronizedList(mutableListOf<Message>())

    embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
        module(history, apiKey)
    }.start(wait = true)
}

fun Application.module(history: MutableList<Message>, apiKey: String) {
    val json = Json { ignoreUnknownKeys = true }
    // Поля со значениями по умолчанию (model, thinking, ...) должны попадать в JSON запроса к DeepSeek
    val clientJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    install(ContentNegotiation) { json(json) }
    install(CallLogging)

    val client = HttpClient(CIO) {
        expectSuccess = true
        install(ClientContentNegotiation) { json(clientJson) }
    }

    routing {
        post("/chat") {
            val request = runCatching { call.receive<ChatRequest>() }.getOrElse {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Тело запроса должно быть JSON с полем \"prompt\""),
                )
                return@post
            }

            if (request.prompt.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Поле \"prompt\" не должно быть пустым"))
                return@post
            }

            // Переводим запрос на английский — DeepSeek лучше работает с английским текстом.
            // При ошибке перевода отправляем оригинал.
            val promptEn = try {
                translate(client, request.prompt, "en")
            } catch (e: Exception) {
                call.application.log.warn("Не удалось перевести запрос на английский (${e.message}) — отправляю оригинал")
                request.prompt
            }

            // Системный промпт + вся история + новый запрос пользователя (всё на английском)
            val messages = synchronized(history) {
                listOf(Message("system", SYSTEM_PROMPT)) + history.toList() + Message("user", promptEn)
            }

            val reply = try {
                val response = client.post(DEEPSEEK_URL) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(DeepSeekRequest(messages = messages))
                }.body<DeepSeekResponse>()
                response.choices.firstOrNull()?.message?.content.orEmpty()
            } catch (e: Exception) {
                call.application.log.error("Ошибка при обращении к DeepSeek API", e)
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("Ошибка при обращении к DeepSeek API: ${e.message}"),
                )
                return@post
            }

            // История хранится на английском: переведённый запрос + ответ DeepSeek
            synchronized(history) {
                history.add(Message("user", promptEn))
                history.add(Message("assistant", reply))
            }

            // Пользователю отвечаем на русском; при ошибке перевода возвращаем английский оригинал
            val replyRu = try {
                translate(client, reply, "ru")
            } catch (e: Exception) {
                call.application.log.warn("Не удалось перевести ответ на русский (${e.message}) — возвращаю оригинал")
                reply
            }

            call.respond(ChatResponse(replyRu))
        }
    }
}
