// Prometheus remote write (version 1): a WriteRequest protobuf, compressed with
// snappy's block format, for POST /v1/telemetry/write on the workers. Written by
// hand so the app needs no protobuf or snappy library; tests/MetricsTest.kt
// checks it against the reference decoders.
//
//   message WriteRequest { repeated TimeSeries timeseries = 1; }
//   message TimeSeries   { repeated Label labels = 1; repeated Sample samples = 2; }
//   message Label        { string name = 1; string value = 2; }
//   message Sample       { double value = 1; int64 timestamp = 2; }  // milliseconds

package at.quickme.prism.core

import java.io.ByteArrayOutputStream
import java.util.TreeMap

object RemoteWrite {
    /** One time series: its labels (with __name__) and its samples, oldest first. */
    class Series(name: String) {
        @JvmField val labels = TreeMap<String, String>()

        /** {value, timestamp ms} */
        @JvmField val samples = ArrayList<DoubleArray>()

        init {
            labels["__name__"] = name
        }

        fun label(name: String, value: String): Series = apply { labels[name] = value }

        fun sample(value: Double, timestampMs: Long): Series = apply { samples.add(doubleArrayOf(value, timestampMs.toDouble())) }

        fun name(): String = labels.getValue("__name__")

        /** The identity of the series: its labels, in order. */
        fun key(): String = labels.toString()
    }

    /** The WriteRequest protobuf. Labels go out sorted by name, as Prometheus requires. */
    @JvmStatic
    fun encode(series: List<Series>): ByteArray {
        val request = Proto()
        for (s in series) {
            if (s.samples.isEmpty()) continue
            val ts = Proto()
            for ((name, value) in s.labels) ts.message(1, Proto().string(1, name).string(2, value))
            for (sample in s.samples) {
                ts.message(2, Proto().fixed64(1, java.lang.Double.doubleToRawLongBits(sample[0])).varintField(2, sample[1].toLong()))
            }
            request.message(1, ts)
        }
        return request.bytes()
    }

    /** Snappy's block format, using only literals: valid, and cheap to produce. */
    @JvmStatic
    fun snappy(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 60000 * 5 + 16)
        writeVarint(out, data.size.toLong())
        var at = 0
        while (at < data.size) {
            val len = minOf(65536, data.size - at)
            val n = len - 1
            when {
                n < 60 -> out.write(n shl 2)
                n < 0x100 -> {
                    out.write(60 shl 2); out.write(n)
                }
                n < 0x10000 -> {
                    out.write(61 shl 2); out.write(n and 0xff); out.write(n ushr 8)
                }
                else -> {
                    out.write(62 shl 2); out.write(n and 0xff); out.write((n ushr 8) and 0xff); out.write(n ushr 16)
                }
            }
            out.write(data, at, len)
            at += 65536
        }
        return out.toByteArray()
    }

    internal fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /** A protobuf message under construction. */
    internal class Proto {
        private val out = ByteArrayOutputStream()

        fun string(field: Int, value: String): Proto {
            val b = value.toByteArray(Charsets.UTF_8)
            writeVarint(out, ((field shl 3) or 2).toLong())
            writeVarint(out, b.size.toLong())
            out.write(b, 0, b.size)
            return this
        }

        fun message(field: Int, value: Proto): Proto {
            val b = value.bytes()
            writeVarint(out, ((field shl 3) or 2).toLong())
            writeVarint(out, b.size.toLong())
            out.write(b, 0, b.size)
            return this
        }

        fun fixed64(field: Int, bits: Long): Proto {
            writeVarint(out, ((field shl 3) or 1).toLong())
            for (i in 0 until 8) out.write((bits ushr (8 * i)).toInt() and 0xff)
            return this
        }

        fun varintField(field: Int, value: Long): Proto {
            writeVarint(out, (field shl 3).toLong())
            writeVarint(out, value)
            return this
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
