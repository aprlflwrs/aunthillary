package net.aprlflwrs.aunthillary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.aprlflwrs.aunthillary.core.AntBuffer
import net.aprlflwrs.aunthillary.core.ColonyState
import net.aprlflwrs.aunthillary.core.FoodPile
import net.aprlflwrs.aunthillary.core.Nest
import net.aprlflwrs.aunthillary.core.ParamInfo
import net.aprlflwrs.aunthillary.core.PheromoneView
import net.aprlflwrs.aunthillary.core.Symbol
import net.aprlflwrs.aunthillary.core.TerrainView
import net.aprlflwrs.aunthillary.core.Voice
import net.aprlflwrs.aunthillary.server.ClientMessage
import net.aprlflwrs.aunthillary.server.Protocol
import java.io.File
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Checks every message against the byte layouts in the Interfaces section of ProjectPlan.md.
 * It also writes the encoded bytes to build/protocol-fixtures/, where the JavaScript decoder's
 * test (src/test/js/protocol.test.mjs) reads them, so both sides are checked against the same bytes.
 */
class ProtocolTest {
    private val fixtures = File("build/protocol-fixtures").apply { mkdirs() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ant frame matches the documented layout`() {
        val ants = AntBuffer(2).apply {
            count = 2
            x[0] = 0f; y[0] = 512f; heading[0] = (PI / 2).toFloat(); flags[0] = (AntBuffer.RETURNING or AntBuffer.CARRYING_FOOD).toByte()
            x[1] = 256f; y[1] = 128f; heading[1] = (-PI / 2).toFloat(); flags[1] = AntBuffer.SCOUT.toByte()
        }
        val encoded = Protocol.encodeAnts(tick = 7, ants = ants)
        assertContentEquals(
            bytes(
                1, 1, 0, 0,            // type, version, reserved
                7, 0, 0, 0,            // tick
                2, 0, 0, 0,            // count
                0x00, 0x00, 0x00, 0x80, // x: 0, 32768 (256 / 512 × 65535 = 32767.5, rounded up)
                0xFF, 0xFF, 0x00, 0x40, // y: 65535, 16384
                64, 192,               // heading: π/2 → 64, −π/2 → 192
                3, 4,                  // flags
            ),
            encoded,
        )
        File(fixtures, "ants.bin").writeBytes(encoded)
    }

    @Test
    fun `pheromone frame matches the documented layout`() {
        val grid = PheromoneView(2, food = floatArrayOf(0f, 0.25f, 1f, 4f), home = floatArrayOf(0.04f, 0f, 0f, 0f))
        val encoded = Protocol.encodePheromone(tick = 9, grid = grid, displayMax = 1.0, outSize = 2)
        assertContentEquals(
            bytes(
                2, 1, 2, 0,          // type, version, n
                9, 0, 0, 0,          // tick
                0, 128, 255, 255,    // food: sqrt(0.25) = 0.5 → 128; 1 and 4 both clamp to 255
                51, 0, 0, 0,         // home: sqrt(0.04) = 0.2 → 51
            ),
            encoded,
        )
        File(fixtures, "pheromone.bin").writeBytes(encoded)
    }

    @Test
    fun `pheromone is averaged down to the frame size`() {
        val src = FloatArray(16) { if (it < 8) 1f else 0f } // bottom two rows 1, top two rows 0
        val out = Protocol.downsample(src, size = 4, outSize = 2)
        assertContentEquals(floatArrayOf(1f, 1f, 0f, 0f), out)
    }

    @Test
    fun `terrain frame matches the documented layout`() {
        val terrain = TerrainView(2, heights = floatArrayOf(0f, 1f, 2f, 4f), blocked = booleanArrayOf(false, true, false, false))
        val encoded = Protocol.encodeTerrain(terrain)
        assertContentEquals(
            bytes(
                3, 1, 2, 0,                  // type, version, n
                0x00, 0x00, 0x80, 0x40,      // maxHeight = 4.0f
                0x00, 0x00, 0x00, 0x40,      // heights: 0, 16384
                0x00, 0x80, 0xFF, 0xFF,      //          32768, 65535
                0, 1, 0, 0,                  // blocked
            ),
            encoded,
        )
        File(fixtures, "terrain.bin").writeBytes(encoded)
    }

    @Test
    fun `state message has a type and the documented fields`() {
        val state = ColonyState(
            tick = 12345,
            nest = Nest(256.0, 256.0, 12.0, 40.5, 18234, 3.2),
            food = listOf(FoodPile(7, 400.0, 310.0, 820.0)),
            voices = listOf(Voice(3, 0, 7, 11000, 0.82, listOf(doubleArrayOf(268.1, 259.0, 1.4, 0.91)))),
            symbols = listOf(Symbol(2, 0.79, 0.61, 0.41, 9000, listOf(3))),
            headings = List(16) { 1.0 / 16 },
        )
        val text = Protocol.encodeState(state)
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("state", obj["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("type", "tick", "nest", "food", "voices", "symbols", "headings"), obj.keys)
        assert(text.contains("\"points\":[[268.1,259.0,1.4,0.91]]")) { text }
        File(fixtures, "state.json").writeText(text)
    }

    @Test
    fun `params message lists every parameter`() {
        val text = Protocol.encodeParams(listOf(ParamInfo("pheromone.evaporation", "Evaporation (per second)", "Pheromone", 0.0, 1.0, 0.001, 0.05, 0.08)))
        assertEquals(
            """{"type":"params","params":[{"key":"pheromone.evaporation","label":"Evaporation (per second)","group":"Pheromone","min":0.0,"max":1.0,"step":0.001,"default":0.05,"value":0.08}]}""",
            text,
        )
    }

    @Test
    fun `browser messages decode`() {
        assertEquals(ClientMessage.PlaceFood(400.0, 310.0), Protocol.decodeClientMessage("""{"type":"placeFood","x":400.0,"y":310.0}"""))
        assertEquals(ClientMessage.SetParam("pheromone.evaporation", 0.08), Protocol.decodeClientMessage("""{"type":"setParam","key":"pheromone.evaporation","value":0.08}"""))
        assertEquals(ClientMessage.Flood, Protocol.decodeClientMessage("""{"type":"flood"}"""))
        assertEquals(ClientMessage.Control("pause"), Protocol.decodeClientMessage("""{"type":"control","action":"pause"}"""))
        assertEquals(ClientMessage.Control("speed", 4.0), Protocol.decodeClientMessage("""{"type":"control","action":"speed","value":4}"""))
        assertNull(Protocol.decodeClientMessage("""{"type":"nonsense"}"""))
        assertNull(Protocol.decodeClientMessage("not json"))
    }
}
