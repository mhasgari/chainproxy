package app.chainproxy.session

import android.content.Context
import app.chainproxy.chain.ChainConfig
import app.chainproxy.chain.ClashApi
import app.chainproxy.chain.Node
import app.chainproxy.chain.RouteManager
import app.chainproxy.chain.androidConfig
import app.chainproxy.chain.decodeProfiles
import app.chainproxy.chain.decodeSubscription
import app.chainproxy.chain.pick
import app.chainproxy.chain.verify
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One connection attempt, the Android counterpart of chain.start_session: fetch the sources, start Lantern, probe and
 * pick nodes, build the sing-box config, hand it to the VPN service ([startCore]), rank the routes and activate the
 * first one that passes verification. Afterwards it watches the active route and exposes next/prev/rescan.
 */
class SessionRunner(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val startCore: suspend (String) -> Unit,
) {
    private var lantern: LanternEngine? = null
    private var routes: RouteManager? = null
    private var cfg: ChainConfig = ChainConfig()

    private fun log(s: String) = AppState.log(s)

    private suspend fun fetch(src: String, label: String): List<Node> = withContext(Dispatchers.IO) {
        val cache = File(ctx.cacheDir, "sub-$label.txt")
        var body: String? = null
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(src)) {
            for (attempt in 1..2) {
                try {
                    val c = URL(src).openConnection() as HttpURLConnection
                    c.connectTimeout = 25000; c.readTimeout = 25000
                    c.setRequestProperty("User-Agent", "v2rayN/6.45")
                    body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    cache.writeText(body)
                    log("[$label] subscription downloaded")
                    break
                } catch (e: Exception) {
                    log("[$label] download failed (${e.javaClass.simpleName}); ${if (attempt == 1) "retrying" else "trying cache"}")
                }
            }
            if (body == null && cache.exists()) body = cache.readText()
        } else body = src                                    // a single pasted link
        val nodes = decodeSubscription(body.orEmpty())
        log("[$label] ${nodes.size} usable nodes")
        nodes
    }

    /** The core refuses outbounds it cannot load (unknown transport, bad key ...): such nodes are dropped before ranking. */
    private fun valid(n: Node): Boolean = try {
        Libbox.checkConfig(buildJsonObject {
            put("outbounds", buildJsonArray { add(JsonObject(n.ob + ("tag" to JsonPrimitive("t")))) })
        }.toString())
        true
    } catch (e: Exception) { false }

    /** Returns null on success, otherwise the reason (shown to the user). */
    suspend fun run(): String? {
        cfg = ConfigStore.load(ctx)
        AppState.clear()
        val lan = if (cfg.lanternEnabled) LanternEngine(ctx, cfg.lanternPort).also { lantern = it; it.start(scope) } else null
        val (profA, badA) = decodeProfiles(cfg.firstProfiles)
        val (profB, badB) = decodeProfiles(cfg.exitProfiles)
        if (badA + badB > 0) log("${badA + badB} profile line(s) not recognized (skipped)")
        val aSub = cfg.firstSubscription.trim()
        val bSub = cfg.exitSubscription.trim()
        if (lan == null && aSub.isEmpty() && bSub.isEmpty() && profA.isEmpty() && profB.isEmpty())
            return "No source configured: enable Lantern, add a subscription link or v2ray profiles in Settings"

        val aNodes = if (aSub.isNotEmpty()) fetch(aSub, "A") else emptyList()
        val bNodes = if (bSub.isNotEmpty()) fetch(bSub, "B") else emptyList()
        log("probing nodes ...")
        val t = cfg.tcpTimeout
        val aS = if (aNodes.isNotEmpty()) pick(aNodes, cfg.topK, t, "A", validator = ::valid) else emptyMap()
        val aP = if (profA.isNotEmpty()) pick(profA, cfg.topKProfiles, t, "A", start = 101, validator = ::valid,
            note = { ok, _ -> log("[A profiles] $ok of ${profA.size} reachable directly (TCP)") }) else emptyMap()
        // With a first hop the exit nodes are dialled from ITS server: keep some the phone cannot reach directly.
        val hasFirstHop = cfg.lanternEnabled || aNodes.isNotEmpty() || profA.isNotEmpty()
        val bS = if (bNodes.isNotEmpty()) pick(bNodes, cfg.topKExit, t, "B", validator = ::valid,
            extraUnreachable = if (hasFirstHop) cfg.topKExit else 0,
            note = { ok, extra -> log("[B] $ok of ${bNodes.size} reachable directly; $extra more kept for the chain test") })
        else emptyMap()
        val bP = if (profB.isNotEmpty()) pick(profB, cfg.topKProfiles, t, "B", start = 101, validator = ::valid,
            note = { ok, _ -> log("[B profiles] $ok of ${profB.size} reachable directly (TCP)") }) else emptyMap()
        val a = LinkedHashMap<String, Node>()
        val b = LinkedHashMap<String, Node>()
        val tier = HashMap<String, Int>()
        if (lan != null) {
            log("waiting for Lantern ...")
            if (lan.waitReady(cfg.lanternTimeout)) {
                a["A0"] = lan.node(); tier["A0"] = 1
                log("Lantern is connected (first hop, priority 1)")
            } else log("Lantern is not available (${lan.error.ifEmpty { "no answer" }}); going on without it")
        }
        for ((part, ti) in listOf(aS to 2, aP to 3)) { a.putAll(part); part.keys.forEach { tier[it] = ti } }
        for ((part, ti) in listOf(bS to 1, bP to 2)) { b.putAll(part); part.keys.forEach { tier[it] = ti } }
        if (a.isEmpty() && b.isEmpty()) return "No usable nodes: check the subscription links / profiles" +
            (lan?.error?.takeIf { it.isNotEmpty() }?.let { " (Lantern: $it)" } ?: "")

        val coreLog = File(ctx.filesDir, "core/sing-box.log").apply { parentFile?.mkdirs(); delete() }
        startCore(androidConfig(cfg, a, b, coreLog.path).toString())
        scope.launch { tailCoreLog(coreLog) }
        log("sing-box started, testing routes ...")
        val api = ClashApi(cfg.apiPort)
        var up = false
        for (i in 1..60) { if (runCatching { api.version() }.isSuccess) { up = true; break }; delay(250) }
        if (!up) return "sing-box API did not come up"
        val rm = RouteManager(cfg, api, { verify(cfg) }, ::log)
        rm.setNodes(a, b, tier)
        routes = rm
        scope.launch { rm.rows.collect { AppState.setRows(it) } }
        rm.buildRank()
        scope.launch { diagnoseProfiles(b, tier) }
        var msg = rm.go(1)
        log(msg)
        // Scans on a phone network are noisy (delay tests time out, Google answers slowly): look again before giving up.
        for (attempt in 1..AUTO_RESCANS) {
            if (rm.idx >= 0 || !scope.isActive) break
            log("nothing usable yet, scanning again ($attempt/$AUTO_RESCANS) ...")
            msg = rm.rescan()
            log(msg)
        }
        if (rm.idx < 0) return msg
        AppState.setSummary(msg)
        scope.launch { watchdog(rm) }
        return null
    }

    /** Shows the core's own warnings/errors (failed connections, DNS errors) in the Log tab; they explain what an app like Chrome hits. */
    private suspend fun tailCoreLog(f: File) {
        var pos = 0L
        val seen = HashMap<String, Int>()
        while (scope.isActive) {
            delay(2000)
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    if (!f.exists() || f.length() < pos) { pos = 0; return@runCatching emptyList<String>() }
                    java.io.RandomAccessFile(f, "r").use { r ->
                        r.seek(pos)
                        val buf = ByteArray(minOf(65536L, f.length() - pos).toInt())
                        r.readFully(buf); pos += buf.size
                        String(buf, Charsets.UTF_8).lines().filter { it.isNotBlank() }
                    }
                }.getOrDefault(emptyList())
            }
            var shown = 0
            for (l in lines) {
                if (" ERROR " !in l && " WARN " !in l) continue
                val key = l.substringAfter("] ", l).take(120)       // an identical error is shown a few times only
                val n = (seen[key] ?: 0) + 1; seen[key] = n
                if (n <= 3 && shown++ < 30) log("core: " + l.substringAfter(" ").take(200))
            }
        }
    }

    /** An exit PROFILE the delay test cannot reach: one direct request through it makes the core log the real error (shown as `core:`). */
    private suspend fun diagnoseProfiles(b: Map<String, Node>, tier: Map<String, Int>) {
        b.keys.forEachIndexed { i, tag ->
            if (tier[tag] != 2) return@forEachIndexed
            scope.launch(Dispatchers.IO) {
                val r = runCatching {
                    val c = URL("https://cp.cloudflare.com/generate_204").openConnection(
                        java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", app.chainproxy.chain.diagPort(cfg, i)))) as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000; c.setRequestProperty("Connection", "close")
                    try { c.responseCode } finally { c.disconnect() }
                }
                log("[diag] $tag: " + r.fold({ "HTTP $it" }, { "${it.javaClass.simpleName}: ${it.message}".take(160) }))
            }
        }
    }

    private suspend fun watchdog(rm: RouteManager) {
        if (cfg.watchdogSeconds <= 0) return
        var fails = 0
        while (scope.isActive) {
            delay(cfg.watchdogSeconds * 1000L)
            val (ok, info) = verify(cfg)
            if (ok) { fails = 0; continue }
            if (++fails < cfg.watchdogFailures) continue
            log("route failed ($info); switching")
            AppState.setSummary(rm.go(1))
            fails = 0
        }
    }

    suspend fun next() = routes?.go(1)?.also { AppState.setSummary(it); log(it) }
    suspend fun prev() = routes?.go(-1)?.also { AppState.setSummary(it); log(it) }
    suspend fun goTo(i: Int) = routes?.goTo(i)?.also { AppState.setSummary(it); log(it) }
    suspend fun rescan() = routes?.rescan()?.also { AppState.setSummary(it); log(it) }

    fun stop() {
        lantern?.stop()
        lantern = null
        routes = null
        AppState.clear()
    }

    private companion object {
        const val AUTO_RESCANS = 2
    }
}
