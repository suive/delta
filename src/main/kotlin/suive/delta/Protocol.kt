package suive.delta

import org.tinylog.kotlin.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

fun processStream(inputStream: InputStream): Sequence<String> {
    return sequence {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var contentLength: Int
        try {
            while (true) {
                val line = reader.readLine() ?: continue
                // Parse headers.
                val split = line.split(":")
                if (split.size != 2) {
                    Logger.warn { "Bad headers" }
                    continue
                }
                val (headerName, headerValue) = split
                if (headerName.trim().toLowerCase() == "content-length") {
                    contentLength = headerValue.trim().toInt()
                    reader.readLine()
                    val message = CharArray(contentLength).also {
                        reader.read(it, 0, contentLength)
                    }
                    yield(String(message))
                }
            }
        } catch (_: IOException) {
            // Ignore closing exception.
        }
    }
}
