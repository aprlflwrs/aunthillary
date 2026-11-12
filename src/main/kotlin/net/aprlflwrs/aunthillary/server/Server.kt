package net.aprlflwrs.aunthillary.server

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.asCoroutineDispatcher
import net.aprlflwrs.aunthillary.core.AntColony
import net.aprlflwrs.aunthillary.trails.TrailDetector
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * The Ktor module, named in application.yaml. It starts the tick loop on a thread of its own,
 * serves the browser client from resources/static at /, and the colony's WebSocket at /ws.
 */
fun Application.module() {
    val log = LoggerFactory.getLogger("aunthillary.server")

    // The real colony and its trail detector, or the fake colony (aunthillary.colony: fake), which
    // has its own hand-made voices and needs no detector.
    val seed = environment.config.propertyOrNull("aunthillary.seed")?.getString()?.toLong() ?: 1L
    val kind = environment.config.propertyOrNull("aunthillary.colony")?.getString() ?: "real"
    val loop = if (kind == "fake") {
        TickLoop(FakeColony(seed))
    } else {
        val colony = AntColony(seed)
        TickLoop(colony, TrailHook(TrailDetector(colony.params)) { voices -> colony.voices = voices })
    }
    log.info("Running the {} colony, seed {}", kind, seed)

    // One dedicated thread owns the colony, so the simulation never shares a thread with network work.
    val colonyThread = Executors.newSingleThreadExecutor { r -> Thread(r, "colony") }.asCoroutineDispatcher()
    launch(colonyThread) { loop.run() }
    monitor.subscribe(ApplicationStopped) { loop.close(); colonyThread.close() }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = 1L shl 20 // browser → server messages are tiny
        masking = false
    }

    routing {
        staticResources("/", "static")

        webSocket("/ws") {
            log.info("Browser connected")
            val outbox = Outbox()
            loop.connect(outbox)
            val sender = launch {
                outbox.drain { message ->
                    when (message) {
                        is Outgoing.Binary -> send(Frame.Binary(true, message.bytes))
                        is Outgoing.Text -> send(Frame.Text(message.text))
                    }
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = Protocol.decodeClientMessage(frame.readText())
                    if (message == null) log.warn("Ignoring unreadable message from browser") else loop.actions.trySend(message)
                }
            } finally {
                loop.disconnect(outbox)
                outbox.close()
                sender.cancel()
                log.info("Browser disconnected")
            }
        }
    }
}
