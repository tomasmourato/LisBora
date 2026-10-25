package pt.lisbora.pipeline.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/** Minimal text GET abstraction so network code can be faked in tests. */
fun interface HttpGet {
    fun getText(url: String): String
}

/** [HttpGet] backed by the JDK client, with a descriptive User-Agent and simple retries; also fetches binaries. */
class JdkHttpGet(
    private val attempts: Int = 3,
    private val timeout: Duration = Duration.ofSeconds(60),
) : HttpGet {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun getText(url: String): String =
        send(url, HttpResponse.BodyHandlers.ofString(), timeout) ?: throw IOException("HTTP 404 for $url")

    /** Body of [url], or null when the server answers 404 (e.g. a missing map tile). */
    fun getBytes(url: String): ByteArray? = send(url, HttpResponse.BodyHandlers.ofByteArray(), timeout)

    /** Streams [url] into [target] (large files); throws on any error, including 404. */
    fun download(url: String, target: Path) {
        send(url, HttpResponse.BodyHandlers.ofFile(target), DOWNLOAD_TIMEOUT) ?: throw IOException("HTTP 404 for $url")
    }

    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>, timeout: Duration): T? {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val request = HttpRequest.newBuilder(URI(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(request, handler)
                when (response.statusCode()) {
                    in 200..299 -> return response.body()
                    404 -> return null
                }
                lastError = IOException("HTTP ${response.statusCode()} for $url")
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < attempts - 1) Thread.sleep(1_000L shl attempt)
        }
        throw IOException("GET failed after $attempts attempts: $url", lastError)
    }

    companion object {
        const val USER_AGENT = "LisBora-pipeline/0.1 (+https://github.com/tomasmourato/LisBora)"
        private val DOWNLOAD_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
