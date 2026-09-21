package com.blainemiller.scripturealone.data.context

/**
 * The offline base map (`Basemap.bin`, built by `Tools/build_context.py` from Natural Earth): land and
 * lake polygons and river lines, each at a coarse and a fine level of detail. Ported from
 * `ScriptureAloneCore/Basemap.swift`; both apps read the same file.
 *
 * The format, all little-endian:
 *
 *     "SABM"  u16 version (1)  u16 layerCount  f32 minLon  f32 minLat  f32 maxLon  f32 maxLat
 *     per layer:  u8 kind  u8 detail  u16 (reserved)  u32 ringCount
 *       per ring:  u32 pointCount  u8 rank  u8 (reserved)  u16 (reserved)
 *         per point:  u16 x  u16 y   — quantized over the bounding box, 0…65535
 *
 * A layer whose kind or detail this reader doesn't know is read past and dropped, so a newer file with
 * an extra layer still opens.
 */
class Basemap(data: ByteArray) {

    enum class Kind(val raw: Int) { LAND(0), LAKE(1), RIVER(2) }
    enum class Detail(val raw: Int) { COARSE(0), FINE(1) }

    /** A longitude (x), latitude (y) pair — Swift's `SIMD2<Double>`. */
    data class Point(val x: Double, val y: Double)

    class Ring(
        /** Natural Earth scale rank: lower is more prominent. */
        val rank: Int,
        /** Longitude, latitude pairs. */
        val points: List<Point>,
    )

    class Layer(val kind: Kind, val detail: Detail, val rings: List<Ring>)

    sealed class DecodeError(message: String) : Exception(message) {
        class BadHeader : DecodeError("not a Scripture Alone base map")
        class Truncated : DecodeError("the base map is truncated")
    }

    /** Longitude/latitude box the coordinates were quantized over. */
    val minLongitude: Double
    val minLatitude: Double
    val maxLongitude: Double
    val maxLatitude: Double
    val layers: List<Layer>

    fun layer(kind: Kind, detail: Detail): Layer? = layers.firstOrNull { it.kind == kind && it.detail == detail }

    init {
        val reader = Reader(data)
        if (!reader.bytes(4).contentEquals("SABM".toByteArray(Charsets.US_ASCII))) throw DecodeError.BadHeader()
        val version = reader.u16()
        if (version != 1) throw DecodeError.BadHeader()
        val layerCount = reader.u16()
        val x0 = reader.f32().toDouble()
        val y0 = reader.f32().toDouble()
        val x1 = reader.f32().toDouble()
        val y1 = reader.f32().toDouble()
        minLongitude = x0
        minLatitude = y0
        maxLongitude = x1
        maxLatitude = y1
        val sx = (x1 - x0) / 65535
        val sy = (y1 - y0) / 65535
        val layers = ArrayList<Layer>()
        repeat(layerCount) {
            val kind = reader.u8()
            val detail = reader.u8()
            reader.u16()
            val ringCount = reader.u32()
            // Counts come from the file, so capacity is capped by what the bytes could possibly hold
            // rather than trusted: a corrupt count then fails as Truncated instead of as an
            // allocation failure. (Swift reserves the full count.)
            val rings = ArrayList<Ring>(minOf(ringCount, reader.remaining / 8L).toInt())
            for (r in 0 until ringCount) {
                val count = reader.u32()
                val rank = reader.u8()
                reader.u8()
                reader.u16()
                val points = ArrayList<Point>(minOf(count, reader.remaining / 4L).toInt())
                for (p in 0 until count) {
                    val x = reader.u16()
                    val y = reader.u16()
                    points += Point(x0 + x * sx, y0 + y * sy)
                }
                rings += Ring(rank, points)
            }
            val k = Kind.entries.firstOrNull { it.raw == kind }
            val d = Detail.entries.firstOrNull { it.raw == detail }
            if (k != null && d != null) layers += Layer(k, d, rings)
        }
        this.layers = layers
    }

    /** Little-endian reads that throw [DecodeError.Truncated] rather than run off the end. */
    private class Reader(private val data: ByteArray) {
        private var offset = 0

        val remaining: Long get() = (data.size - offset).toLong()

        private fun take(size: Int): Int {
            if (offset + size > data.size) throw DecodeError.Truncated()
            val at = offset
            offset += size
            return at
        }

        fun u8(): Int = data[take(1)].toInt() and 0xFF

        fun u16(): Int {
            val at = take(2)
            return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        }

        fun u32(): Long {
            val at = take(4)
            return (data[at].toLong() and 0xFF) or ((data[at + 1].toLong() and 0xFF) shl 8) or
                ((data[at + 2].toLong() and 0xFF) shl 16) or ((data[at + 3].toLong() and 0xFF) shl 24)
        }

        fun f32(): Float = Float.fromBits(u32().toInt())

        fun bytes(count: Int): ByteArray {
            val at = take(count)
            return data.copyOfRange(at, at + count)
        }
    }
}
