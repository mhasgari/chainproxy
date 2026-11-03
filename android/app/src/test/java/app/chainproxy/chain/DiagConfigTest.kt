package app.chainproxy.chain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagConfigTest {
    private fun node(n: String, port: Int) = Node(n, buildJsonObject { put("type", "socks"); put("server", "h$port"); put("server_port", port) })

    @Test
    fun everyExitNodeGetsALoopbackDiagInboundAndRule() {
        val cfg = ChainConfig()
        val c = androidConfig(cfg, mapOf("A1" to node("a", 1)), linkedMapOf("B1" to node("x", 2), "B2" to node("y", 3)))
        val inbounds = c.getValue("inbounds").jsonArray.map { it.jsonObject }
        val diag = inbounds.filter { it.getValue("tag").jsonPrimitive.content.startsWith("diag-") }
        assertEquals(listOf("diag-0", "diag-1"), diag.map { it.getValue("tag").jsonPrimitive.content })
        assertEquals(listOf(diagPort(cfg, 0), diagPort(cfg, 1)), diag.map { it.getValue("listen_port").jsonPrimitive.content.toInt() })
        assertEquals(setOf("127.0.0.1"), diag.map { it.getValue("listen").jsonPrimitive.content }.toSet())
        val rules = c.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
        val toB2 = rules.first { (it["inbound"] as? JsonArray)?.get(0)?.jsonPrimitive?.content == "diag-1" }
        assertEquals("B2", toB2.getValue("outbound").jsonPrimitive.content)
    }
}
