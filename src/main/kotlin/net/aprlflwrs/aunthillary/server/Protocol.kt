package net.aprlflwrs.aunthillary.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import net.aprlflwrs.aunthillary.core.AntBuffer
import net.aprlflwrs.aunthillary.core.ColonyState
import net.aprlflwrs.aunthillary.core.ParamInfo
import net.aprlflwrs.aunthillary.core.PheromoneView
import net.aprlflwrs.aunthillary.core.TerrainView
import net.aprlflwrs.aunthillary.core.World
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Encoders for every server → browser message, and the decoder for browser → server messages.
 * The byte layouts are the contract in the Interfaces section of ProjectPlan.md; ProtocolTest
 * checks them byte for byte, and the JavaScript decoder (static/net/protocol.js) is tested
 * against bytes produced here.
 */
object Protocol {
    const val TYPE_ANTS: Byte = 1
    const val TYPE_PHEROMONE: Byte = 2
    const val TYPE_TERRAIN: Byte = 3
    const val VERSION: Byte = 1

    /** The pheromone frame is always sent at this resolution, whatever the colony's own grid is. */
    const val PHEROMONE_FRAME_SIZE = 128

    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    // ---- binary frames ----

    /** Ant frame: header, then one contiguous block per field (x, y, heading, flags). */
    fun encodeAnts(tick: Long, ants: AntBuffer): ByteArray {
        val n = ants.count
        val buf = header(12 + 6 * n, TYPE_ANTS)
        buf.putShort(0) // reserved
        buf.putInt(tick.toInt())
        buf.putInt(n)
        for (i in 0 until n) buf.putShort(quantizePosition(ants.x[i]))
        for (i in 0 until n) buf.putShort(quantizePosition(ants.y[i]))
        for (i in 0 until n) buf.put(quantizeHeading(ants.heading[i]))
        buf.put(ants.flags, 0, n)
        return buf.array()
    }

    /**
     * Pheromone frame at [PHEROMONE_FRAME_SIZE]² (or [outSize]², for tests). Each byte is
     * round(255 × sqrt(min(value / displayMax, 1))); the square root keeps faint trails visible.
     */
    fun encodePheromone(
        tick: Long,
        grid: PheromoneView,
        displayMax: Double,
        outSize: Int = PHEROMONE_FRAME_SIZE,
    ): ByteArray {
        val cells = outSize * outSize
        val buf = header(8 + 2 * cells, TYPE_PHEROMONE)
        buf.putShort(outSize.toShort())
        buf.putInt(tick.toInt())
        val food = downsample(grid.food, grid.size, outSize)
        val home = downsample(grid.home, grid.size, outSize)
        for (v in food) buf.put(pheromoneByte(v, displayMax))
        for (v in home) buf.put(pheromoneByte(v, displayMax))
        return buf.array()
    }

    /** Terrain frame: heights as u16 scaled to maxHeight, then one byte per cell for blocked. */
    fun encodeTerrain(terrain: TerrainView): ByteArray {
        val cells = terrain.size * terrain.size
        val maxHeight = (terrain.heights.maxOrNull() ?: 0f).coerceAtLeast(1e-6f)
        val buf = header(8 + 3 * cells, TYPE_TERRAIN)
        buf.putShort(terrain.size.toShort())
        buf.putFloat(maxHeight)
        for (h in terrain.heights) {
            buf.putShort((h.coerceIn(0f, maxHeight) / maxHeight * 65535f).roundToInt().toShort())
        }
        for (b in terrain.blocked) buf.put(if (b) 1 else 0)
        return buf.array()
    }

    // ---- JSON messages ----

    fun encodeState(state: ColonyState): String = withType("state", json.encodeToJsonElement(state).jsonObject)

    fun encodeParams(params: List<ParamInfo>): String = json.encodeToString(ParamsMessage(params = params))

    /** Returns null for a message that can't be parsed, so one bad message can't take the connection down. */
    fun decodeClientMessage(text: String): ClientMessage? =
        try {
            json.decodeFromString(ClientMessage.serializer(), text)
        } catch (e: Exception) {
            null
        }

    // ---- helpers ----

    fun quantizePosition(v: Float): Short =
        (v / World.SIZE * 65535.0).roundToInt().coerceIn(0, 65535).toShort()

    fun quantizeHeading(h: Float): Byte =
        (Math.floorMod((h / (2 * PI) * 256.0).roundToInt(), 256)).toByte()

    fun pheromoneByte(v: Float, displayMax: Double): Byte {
        val t = (v / displayMax).coerceIn(0.0, 1.0)
        return (255.0 * sqrt(t)).roundToInt().toByte()
    }

    /**
     * Averages blocks of cells down to [outSize]². Assumes [size] is a multiple of [outSize]
     * (true for every resolution the plan benchmarks: 256, 512, 1024 → 128).
     */
    fun downsample(src: FloatArray, size: Int, outSize: Int): FloatArray {
        if (size == outSize) return src
        require(size % outSize == 0) { "Grid size $size is not a multiple of $outSize" }
        val f = size / outSize
        val out = FloatArray(outSize * outSize)
        for (row in 0 until size) {
            val orow = row / f
            for (col in 0 until size) out[orow * outSize + col / f] += src[row * size + col]
        }
        val norm = 1f / (f * f)
        for (i in out.indices) out[i] *= norm
        return out
    }

    private fun header(bytes: Int, type: Byte): ByteBuffer =
        ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).put(type).put(VERSION)

    private fun withType(type: String, body: JsonObject): String =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            body.forEach { (k, v) -> put(k, v) }
        }.toString()
}

@Serializable
data class ParamsMessage(val type: String = "params", val params: List<ParamInfo>)

/** Browser → server messages. The JSON "type" field picks the subclass. */
@Serializable
sealed class ClientMessage {
    @Serializable
    @SerialName("placeFood")
    data class PlaceFood(val x: Double, val y: Double) : ClientMessage()

    @Serializable
    @SerialName("setParam")
    data class SetParam(val key: String, val value: Double) : ClientMessage()

    @Serializable
    @SerialName("flood")
    data object Flood : ClientMessage()

    /** action is pause, resume, step, reset, or speed (with value; 1 is real time). */
    @Serializable
    @SerialName("control")
    data class Control(val action: String, val value: Double? = null) : ClientMessage()
}
