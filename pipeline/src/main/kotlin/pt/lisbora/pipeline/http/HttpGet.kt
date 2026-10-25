package pt.lisbora.pipeline.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Minimal text GET abstraction so network code can be faked in tests. */
fun interface HttpGet {
    fun getText(url: String): String
}

/** [HttpGet] backed by the JDK client, with a descriptive User-Agent and simple retries. */
class JdkHttpGet(
    private val attempts: Int = 3,
    private val timeout: Duration = Duration.ofSeconds(60),
) : HttpGet {

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun getText(url: String): String {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                val request = HttpRequest.newBuilder(URI(url))
                    .timeout(timeout)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) return response.body()
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
    }
}
