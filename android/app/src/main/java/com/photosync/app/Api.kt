package com.photosync.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Talks to server.py. Mirrors its resumable upload/download protocol exactly.
 * All calls are blocking; callers run them on a background dispatcher.
 */
class Api(private val base: String, private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)   // large uploads
        .build()

    private val JSON = "application/json".toMediaType()

    private fun Request.Builder.auth(): Request.Builder {
        if (apiKey.isNotEmpty()) header("X-Api-Key", apiKey)
        return this
    }

    /**
     * Retry a network call a few times on connection/IO failures. A cold
     * Tailscale tunnel often refuses the first connection and accepts the
     * retry, so without this a single blip aborts the whole sync. Only
     * IOExceptions (connect/read failures) retry; HTTP error codes do not.
     */
    private fun <T> withRetry(attempts: Int = 3, block: () -> T): T {
        var last: java.io.IOException? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                last = e
                if (i < attempts - 1) Thread.sleep(1000L * (i + 1))   // 1s, then 2s
            }
        }
        throw last ?: java.io.IOException("retry failed")
    }

    fun ping(): Boolean = try {
        client.newCall(Request.Builder().url("$base/healthz").auth().build())
            .execute().use { it.isSuccessful }
    } catch (e: Exception) { false }

    /** Like ping(), but returns null on success or the exact failure reason. */
    fun pingError(): String? = try {
        client.newCall(Request.Builder().url("$base/healthz").auth().build())
            .execute().use { if (it.isSuccessful) null else "server returned HTTP ${it.code}" }
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message ?: "no detail"}"
    }

    /** Ask which of these hashes the server already has. */
    fun check(hashes: List<String>): Set<String> = withRetry {
        val body = JSONObject().put("hashes", JSONArray(hashes)).toString()
            .toRequestBody(JSON)
        client.newCall(
            Request.Builder().url("$base/api/check").auth().post(body).build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return@withRetry emptySet<String>()
            val arr = JSONObject(resp.body!!.string()).getJSONArray("have")
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        }
    }

    data class Status(val complete: Boolean, val offset: Long)

    fun uploadStatus(hash: String): Status = withRetry {
        client.newCall(
            Request.Builder().url("$base/api/upload/status?hash=$hash").auth().get().build()
        ).execute().use { resp ->
            val o = JSONObject(resp.body!!.string())
            Status(o.getBoolean("complete"), o.getLong("offset"))
        }
    }

    /**
     * Resumable upload. Reads the file via [openStream], picks up from whatever
     * the server already has, and pushes fixed size chunks. Returns true when
     * the whole file is stored. [onBytes] reports cumulative bytes sent.
     */
    fun upload(
        hash: String, ext: String, name: String, type: String, taken: Long,
        total: Long, chunkSize: Long = 4L * 1024 * 1024,
        openStream: () -> InputStream, onBytes: (Long) -> Unit = {}
    ): Boolean {
        var status = uploadStatus(hash)
        if (status.complete) return true
        var offset = status.offset.coerceAtLeast(0)

        openStream().use { input ->
            // skip to the resume point
            var skipped = 0L
            while (skipped < offset) {
                val s = input.skip(offset - skipped)
                if (s <= 0) break
                skipped += s
            }
            val buf = ByteArray(chunkSize.toInt())
            while (offset < total) {
                var filled = 0
                while (filled < buf.size) {
                    val r = input.read(buf, filled, buf.size - filled)
                    if (r <= 0) break
                    filled += r
                }
                if (filled == 0) break
                val chunk = buf.copyOf(filled).toRequestBody(null)
                val url = "$base/api/upload?hash=$hash&ext=$ext" +
                        "&name=${enc(name)}&type=$type&taken=$taken"
                val req = Request.Builder().url(url).auth()
                    .header("Upload-Offset", offset.toString())
                    .header("Upload-Total", total.toString())
                    .put(chunk).build()
                // Resending the same offset is safe: the upload is resumable and
                // the server replies 409 with its true offset if it's ahead.
                val (code, bodyStr) = withRetry {
                    client.newCall(req).execute().use { resp -> resp.code to resp.body!!.string() }
                }
                val o = JSONObject(bodyStr)
                when {
                    code == 409 -> offset = o.getLong("offset")   // server out of sync; restart there
                    o.getBoolean("complete") -> offset = total
                    else -> offset = o.getLong("offset")
                }
                onBytes(offset)
            }
        }
        return uploadStatus(hash).complete
    }

    data class RemoteItem(
        val hash: String, val ext: String, val name: String,
        val size: Long, val type: String
    )

    /** Page through everything on the server. */
    fun listAll(): List<RemoteItem> {
        val out = ArrayList<RemoteItem>()
        var offset = 0
        val limit = 2000
        while (true) {
            client.newCall(
                Request.Builder().url("$base/api/media?limit=$limit&offset=$offset")
                    .auth().get().build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) return out
                val root = JSONObject(resp.body!!.string())
                val items = root.getJSONArray("items")
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    out.add(
                        RemoteItem(
                            it.getString("hash"),
                            it.optString("ext", ""),
                            it.optString("orig_name", it.getString("hash")),
                            it.optLong("size", 0),
                            it.optString("media_type", "image")
                        )
                    )
                }
                val total = root.getInt("total")
                offset += items.length()
                if (items.length() == 0 || offset >= total) return out
            }
        }
    }

    /**
     * Resumable download via HTTP Range. [sink] receives appended bytes and
     * must report its current size via [haveBytes] so we can resume.
     */
    fun download(
        hash: String, total: Long, haveBytes: Long,
        sink: (ByteArray, Int) -> Unit, onBytes: (Long) -> Unit = {}
    ): Boolean {
        val req = Request.Builder().url("$base/api/download/$hash").auth()
            .apply { if (haveBytes > 0) header("Range", "bytes=$haveBytes-") }
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val body = resp.body ?: return false
            var got = haveBytes
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    sink(buf, r)
                    got += r
                    onBytes(got)
                }
            }
            return total <= 0 || got >= total
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
