package com.dotmusic.app

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small blocking HTTP helpers for the updater; always called off the main thread. */
object Net {
    private const val AGENT = "DotPhone/1.0 (Android)"

    fun get(url: String, timeoutMs: Int = 12_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code: ${body.take(300)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    /** Stream a URL into a file (a downloaded update); also off the main thread. */
    fun download(url: String, out: File, timeoutMs: Int = 60_000) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", AGENT)
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }
}
