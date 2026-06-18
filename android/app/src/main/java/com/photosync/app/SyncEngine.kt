package com.photosync.app

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The brain of the app. Stateless-ish helper that:
 *   - hashes local media (cached, so each file is hashed at most once),
 *   - reconciles with the server (which hashes are already there),
 *   - pushes the missing ones up and pulls the missing ones down.
 *
 * Everything reports progress through [Progress] so the worker can drive a
 * notification and the UI can show counts.
 */
class SyncEngine(private val ctx: Context) {

    data class Progress(
        val phase: String,          // "hashing" | "checking" | "uploading" | "downloading" | "done"
        val done: Int,
        val total: Int,
        val label: String = ""
    )

    private val db = Db.get(ctx).cache()
    private val prefs = Prefs(ctx)

    private fun api(): Api = Api(prefs.serverUrl, prefs.apiKey)

    // ---- hashing with cache --------------------------------------------------

    private fun sha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        stream.use {
            val buf = ByteArray(1 shl 20)
            while (true) {
                val r = it.read(buf); if (r <= 0) break; md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Ensure every item has a cached hash. Returns mediaId -> hash. */
    suspend fun ensureHashes(
        items: List<MediaItem>, onProgress: (Progress) -> Unit
    ): Map<Long, String> {
        val map = HashMap<Long, String>(items.size)
        items.forEachIndexed { i, m ->
            val cached = db.get(m.id)
            val hash = if (cached != null &&
                cached.dateModified == m.dateModified && cached.size == m.size
            ) {
                cached.hash
            } else {
                val h = ctx.contentResolver.openInputStream(m.uri)?.let { sha256(it) }
                    ?: return@forEachIndexed
                db.put(CacheEntry(m.id, m.dateModified, m.size, h, cached?.synced ?: false))
                h
            }
            map[m.id] = hash
            if (i % 10 == 0 || i == items.size - 1)
                onProgress(Progress("hashing", i + 1, items.size, m.name))
        }
        return map
    }

    // ---- reconcile -----------------------------------------------------------

    /** Mark cache entries synced for hashes the server already has. */
    suspend fun refreshSyncState(hashes: Collection<String>, onProgress: (Progress) -> Unit) {
        val api = api()
        val list = hashes.distinct()
        var done = 0
        list.chunked(800).forEach { batch ->
            val have = api.check(batch)
            have.forEach { db.markSyncedByHash(it) }
            done += batch.size
            onProgress(Progress("checking", done, list.size))
        }
    }

    // ---- push ----------------------------------------------------------------

    /** Upload everything the server does not yet have. Returns count uploaded. */
    suspend fun push(onProgress: (Progress) -> Unit): Int {
        val excluded = db.excludedIds().toHashSet()
        val items = MediaRepo.scanAll(ctx).filterNot { it.id in excluded }
        val hashes = ensureHashes(items, onProgress)

        // Check in batches instead of one giant POST. Sending all ~10k hashes
        // in a single request (~0.6 MB) stalls over a cold Tailscale tunnel and
        // hangs the whole sync at "checking 0/1". Batching keeps each request
        // small, lets progress advance, and survives a slow link.
        val allHashes = hashes.values.distinct()
        val present = HashSet<String>()
        var checked = 0
        allHashes.chunked(800).forEach { batch ->
            present.addAll(api().check(batch))
            checked += batch.size
            onProgress(Progress("checking", checked, allHashes.size))
        }
        present.forEach { db.markSyncedByHash(it) }

        // Smallest files first: the synced count climbs fast and big videos
        // are deferred to the end rather than stalling everything up front.
        val toUpload = items
            .filter { hashes[it.id]?.let { h -> h !in present } == true }
            .sortedBy { it.size }
        var uploaded = 0
        toUpload.forEachIndexed { i, m ->
            currentCoroutineContext().ensureActive()   // bail out fast if the user pressed Stop
            val h = hashes[m.id] ?: return@forEachIndexed
            val ext = m.name.substringAfterLast('.', "").lowercase()
            val type = if (m.isVideo) "video" else "image"
            onProgress(Progress("uploading", i, toUpload.size, m.name))
            val ok = api().upload(
                hash = h, ext = ext, name = m.name, type = type,
                taken = m.dateModified * 1000, total = m.size,
                openStream = { ctx.contentResolver.openInputStream(m.uri)!! }
            )
            if (ok) {
                db.markSyncedByHash(h)
                uploaded++
            }
            onProgress(Progress("uploading", i + 1, toUpload.size, m.name))
        }
        onProgress(Progress("done", toUpload.size, toUpload.size))
        return uploaded
    }

    // ---- pull ----------------------------------------------------------------

    /** Download everything on the server that is not on this phone. Returns count. */
    suspend fun pull(onProgress: (Progress) -> Unit): Int {
        val api = api()
        onProgress(Progress("checking", 0, 1, "listing server"))
        val remote = api.listAll()
        val localHashes = db.knownHashes().toHashSet()

        val missing = remote.filter { it.hash !in localHashes }
        var pulled = 0
        val tmpDir = File(ctx.cacheDir, "pull").apply { mkdirs() }

        missing.forEachIndexed { i, item ->
            onProgress(Progress("downloading", i, missing.size, item.name))
            val part = File(tmpDir, "${item.hash}.part")
            val have = if (part.exists()) part.length() else 0L
            val out = java.io.FileOutputStream(part, /*append=*/ have > 0)
            val ok = try {
                api.download(item.hash, item.size, have,
                    sink = { b, n -> out.write(b, 0, n) })
            } finally { out.close() }

            if (ok && verify(part, item.hash)) {
                val saved = MediaRepo.insertDownloaded(
                    ctx, item.name, item.type == "video"
                ) { os -> part.inputStream().use { it.copyTo(os) } }
                if (saved) {
                    // record it so the gallery and future pulls know we have it
                    db.put(
                        CacheEntry(
                            mediaId = -(item.hash.hashCode().toLong()), // synthetic id
                            dateModified = 0, size = item.size,
                            hash = item.hash, synced = true
                        )
                    )
                    part.delete()
                    pulled++
                }
            }
            onProgress(Progress("downloading", i + 1, missing.size, item.name))
        }
        onProgress(Progress("done", missing.size, missing.size))
        return pulled
    }

    private fun verify(f: File, expected: String): Boolean =
        runCatching { sha256(f.inputStream()) == expected }.getOrDefault(false)
}
