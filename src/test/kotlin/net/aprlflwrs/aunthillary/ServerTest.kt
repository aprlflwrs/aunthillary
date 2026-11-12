package net.aprlflwrs.aunthillary

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun `serves the client at the root`() = testApplication {
        configure() // loads application.yaml, which names our module
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("importmap" in response.bodyAsText())
    }

    @Test
    fun `a new browser receives params, terrain, state and ants`() = testApplication {
        configure()
        val ws = createClient { install(WebSockets) }
        ws.webSocket("/ws") {
            val seen = mutableSetOf<String>()
            withTimeout(5_000) {
                while (seen.size < 5) {
                    when (val frame = incoming.receive()) {
                        is Frame.Text -> seen += Regex("\"type\":\"(\\w+)\"").find(frame.readText())!!.groupValues[1]
                        is Frame.Binary -> seen += when (frame.data[0].toInt()) { 1 -> "ants"; 2 -> "pheromone"; 3 -> "terrain"; else -> "?" }
                        else -> {}
                    }
                }
            }
            assertEquals(setOf("params", "terrain", "state", "ants", "pheromone"), seen)
        }
    }
}
