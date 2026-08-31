package mint.app.data

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

class NewPipeDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = OkRequest.Builder()
            .url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        when (request.httpMethod()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val body = request.dataToSend()
                    ?.toRequestBody(JSON_MEDIA_TYPE)
                    ?: ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
                builder.post(body)
            }
            else -> throw IllegalArgumentException("Unsupported HTTP method: ${request.httpMethod()}")
        }

        val response = client.newCall(builder.build()).execute()
        val body = response.body?.string() ?: ""
        val latestUrl = response.request.url.toString()
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            latestUrl,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE: MediaType = "application/json".toMediaType()
    }
}
