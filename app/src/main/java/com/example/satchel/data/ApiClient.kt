package com.example.satchel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.IOException
import okio.source
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An error to show the person. status 0 means the server could not be reached. */
class ApiException(val status: Int, message: String) : Exception(message)

/** A file chosen for upload, read only when the request is sent. */
class UploadSource(val name: String, val size: Long, val mimeType: String, val open: () -> InputStream)

/**
 * The Satchel API (the same one the web frontend uses). Every call after login
 * sends the bearer token; a 401 means the login expired, and [onUnauthorized]
 * sends the person back to the login screen.
 */
class ApiClient(
    baseUrl: String,
    private val token: () -> String?,
    private val onUnauthorized: () -> Unit,
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').toHttpUrl()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)    // AI questions can take up to ~40 s the first time
        .writeTimeout(120, TimeUnit.SECONDS)  // uploads of up to 25 MB
        .build()

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    // ------------------------------ accounts ------------------------------ //

    suspend fun login(email: String, password: String): TokenResponse =
        post("auth/login", buildJsonObject { put("email", email); put("password", password) })

    suspend fun register(email: String, fullName: String, password: String): TokenResponse =
        post("auth/register", buildJsonObject {
            put("email", email); put("full_name", fullName); put("password", password)
        })

    suspend fun me(): User = get(url("auth/me"))

    suspend fun providers(): List<Provider> = get(url("auth/providers"))

    /** Swaps the one-time code from satchel://auth/callback for a login token. */
    suspend fun appToken(code: String, verifier: String): TokenResponse =
        post("auth/app/token", buildJsonObject { put("code", code); put("code_verifier", verifier) })

    suspend fun changePassword(current: String, new: String) {
        val body = buildJsonObject { put("current_password", current); put("new_password", new) }
        send(Request.Builder().url(url("auth/change-password")).post(body.toBody()))
    }

    /** Where a Custom Tab starts signing in with an outside provider. */
    fun ssoStartUrl(providerId: String, challenge: String): String =
        url("auth/oidc/$providerId/start", "app_challenge" to challenge, "next" to "/").toString()

    // ------------------------------ browsing ------------------------------ //

    suspend fun hierarchy(): Hierarchy = get(url("hierarchy"))

    suspend fun documents(filters: Filters, page: Int, pageSize: Int): DocumentPage =
        get(url("documents", *filters.asQuery().toTypedArray(), "page" to "$page", "page_size" to "$pageSize"))

    /** method: "tfidf" (the engine's content ranking) or "keyword" (the baseline). */
    suspend fun search(query: String, filters: Filters, method: String, topK: Int = 20): SearchResults =
        get(url("search", "q" to query, "method" to method, "top_k" to "$topK", *filters.asQuery().toTypedArray()))

    suspend fun document(id: Int, related: Int = 8): DocumentDetail =
        get(url("documents/$id", "related" to "$related"))

    /** method: "tfidf", or "ai" when Hierarchy.aiQuizAvailable. */
    suspend fun quiz(id: Int, count: Int, method: String): Quiz =
        get(url("documents/$id/quiz", "count" to "$count", "method" to method))

    // ------------------------------- uploads ------------------------------ //

    suspend fun myUploads(): List<Document> = get(url("documents/mine"))

    suspend fun upload(
        source: UploadSource, title: String, courseId: Int, documentTypeId: Int, academicYear: String?,
    ): UploadResponse {
        val file = object : RequestBody() {
            override fun contentType(): MediaType = source.mimeType.toMediaType()
            override fun contentLength(): Long = source.size
            override fun writeTo(sink: BufferedSink) {
                source.open().source().use { sink.writeAll(it) }
            }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", source.name, file)
            .addFormDataPart("title", title)
            .addFormDataPart("course_id", "$courseId")
            .addFormDataPart("document_type_id", "$documentTypeId")
            .apply { if (academicYear != null) addFormDataPart("academic_year", academicYear) }
            .build()
        return decode(send(Request.Builder().url(url("documents")).post(form)))
    }

    /** Saves a document's file to [target], replacing any earlier copy. */
    suspend fun download(id: Int, target: File) {
        val request = authorised(Request.Builder().url(url("documents/$id/file"))).build()
        val response = execute(request)
        withContext(Dispatchers.IO) {
            response.use {
                if (!it.isSuccessful) throw errorFor(it)
                target.parentFile?.mkdirs()
                val partial = File(target.path + ".part")
                partial.outputStream().use { out -> it.body.byteStream().copyTo(out) }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    throw ApiException(0, "Could not save the file on this phone.")
                }
            }
        }
    }

    // ------------------------------ plumbing ------------------------------ //

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegments(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend inline fun <reified T> get(url: HttpUrl): T = decode(send(Request.Builder().url(url)))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        decode(send(Request.Builder().url(url(path)).post(body.toBody())))

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: IllegalArgumentException) {
        throw ApiException(0, "The server sent an answer this app does not understand.")
    }

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON)

    private fun authorised(builder: Request.Builder): Request.Builder =
        token()?.let { builder.header("Authorization", "Bearer $it") } ?: builder

    /** Sends a request and returns the body text of a successful response. */
    private suspend fun send(builder: Request.Builder): String {
        val response = execute(authorised(builder).build())
        return withContext(Dispatchers.IO) {
            response.use { if (it.isSuccessful) it.body.string() else throw errorFor(it) }
        }
    }

    private fun errorFor(response: Response): ApiException {
        if (response.code == 401 && token() != null) onUnauthorized()
        val text = runCatching { response.body.string() }.getOrDefault("")
        return ApiException(response.code, detailMessage(text) ?: "Request failed (${response.code})")
    }

    /** FastAPI errors: {"detail": "message"} or {"detail": [{"loc": [...], "msg": "..."}]}. */
    private fun detailMessage(text: String): String? {
        val detail = runCatching { json.parseToJsonElement(text).jsonObject["detail"] }.getOrNull() ?: return null
        return when (detail) {
            is JsonPrimitive -> detail.contentOrNull
            is JsonArray -> detail.joinToString("; ") { item ->
                val obj = item as? JsonObject
                val field = (obj?.get("loc") as? JsonArray)?.drop(1)?.joinToString(".") { it.jsonPrimitive.content }
                val msg = obj?.get("msg")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (field.isNullOrEmpty()) msg else "$field: $msg"
            }
            else -> null
        }
    }

    /** Runs the call off the main thread; cancelling the coroutine cancels the request. */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(ApiException(0, "Cannot reach the server. Check your connection and try again."))
            }
        })
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
