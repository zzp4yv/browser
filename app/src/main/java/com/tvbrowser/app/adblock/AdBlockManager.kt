package com.tvbrowser.app.adblock

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Host-based ad & malware blocker.
 *
 * Blocking is domain-based (matches the request host or any of its parent
 * domains against a public blocklist) rather than URL/pattern based, so it
 * never has to parse or rewrite video byte-range requests - it either lets a
 * whole host through untouched or refuses it outright. [criticalAllowlist]
 * always wins, so a streaming CDN can never be blocked even if a public list
 * misclassifies it.
 */
object AdBlockManager {

    private const val TAG = "AdBlockManager"
    private const val REMOTE_LIST_URL =
        "https://raw.githubusercontent.com/anudeepND/blacklist/master/adservers.txt"
    private const val CACHE_FILE_NAME = "adblock_remote_cache.txt"
    private const val MIN_REMOTE_DOMAINS = 1000

    private val lock = Mutex()
    @Volatile private var blockedDomains: HashSet<String> = HashSet()
    @Volatile private var allowedDomains: HashSet<String> = HashSet()
    @Volatile private var ready = false

    private val blockedCounter = AtomicInteger(0)
    val blockedCount: Int get() = blockedCounter.get()

    fun resetCounter() = blockedCounter.set(0)

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            loadBundledLists(appContext)
            ready = true
            refreshFromRemote(appContext)
        }
    }

    /** true if [host] (or a parent domain of it) is on the blocklist and not allow-listed. */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank() || !ready) return false
        val normalized = host.lowercase().removeSuffix(".")
        if (matchesAnyParent(normalized, allowedDomains)) return false
        val blocked = matchesAnyParent(normalized, blockedDomains)
        if (blocked) blockedCounter.incrementAndGet()
        return blocked
    }

    fun isBlockedUrl(url: String?): Boolean {
        val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: return false
        return isBlocked(host)
    }

    /** Walks "a.b.c.example.com" -> "b.c.example.com" -> "c.example.com" -> "example.com" -> "com" */
    private fun matchesAnyParent(host: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var current = host
        while (true) {
            if (set.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    private fun loadBundledLists(context: Context) {
        val base = HashSet<String>(50_000)
        val allow = HashSet<String>(256)
        try {
            context.assets.open("adblock/adservers.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> lineToDomain(line)?.let { base.add(it) } }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load bundled adservers.txt", e)
        }
        try {
            context.assets.open("adblock/whitelist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> lineToDomain(line)?.let { allow.add(it) } }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load bundled whitelist.txt", e)
        }
        try {
            context.assets.open("adblock/critical_allowlist.txt").bufferedReader().useLines { lines ->
                lines.forEach { line -> lineToDomain(line)?.let { allow.add(it) } }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to load critical_allowlist.txt", e)
        }
        // A cached remote list from a previous run, if we have one, is merged in immediately
        // so a freshly-launched app benefits from the latest update without waiting on network.
        readCache(context)?.let { base.addAll(it) }

        blockedDomains = base
        allowedDomains = allow
        Log.i(TAG, "Loaded ${base.size} blocked domains, ${allow.size} allow-listed domains")
    }

    private fun lineToDomain(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
        return line.lowercase()
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE_NAME)

    private fun readCache(context: Context): Set<String>? {
        val file = cacheFile(context)
        if (!file.exists()) return null
        return try {
            file.bufferedReader().useLines { lines ->
                lines.mapNotNull { lineToDomain(it) }.toHashSet()
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Pulls the latest public ad/malware host list from GitHub and merges it in.
     * Safe to call repeatedly; it is throttled implicitly by being invoked once
     * per app foreground session. Never removes the bundled baseline, so a
     * network failure just means "no update this time", not "no ad blocking".
     */
    suspend fun refreshFromRemote(context: Context) = withContext(Dispatchers.IO) {
        try {
            val url = URL(REMOTE_LIST_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TVBrowser-AdBlock")
            connection.inputStream.use { stream ->
                val domains = stream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { lineToDomain(hostsLineToDomain(it)) }.toHashSet()
                }
                if (domains.size >= MIN_REMOTE_DOMAINS) {
                    lock.withLock {
                        val merged = HashSet(blockedDomains)
                        merged.addAll(domains)
                        blockedDomains = merged
                    }
                    cacheFile(context).writeText(domains.joinToString("\n"))
                    Log.i(TAG, "Updated blocklist from remote: ${domains.size} domains")
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Remote blocklist refresh failed (will keep bundled list): ${e.message}")
        }
    }

    /** Converts a "0.0.0.0 example.com" hosts-file line into a bare domain, or passes through a bare domain line. */
    private fun hostsLineToDomain(rawLine: String): String {
        val line = rawLine.trim()
        val parts = line.split(Regex("\\s+"))
        return if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
            parts[1]
        } else {
            line
        }
    }
}
