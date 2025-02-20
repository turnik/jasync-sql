package com.github.jasync.sql.db.postgresql.column

import com.github.jasync.sql.db.column.BigDecimalEncoderDecoder
import com.github.jasync.sql.db.column.ColumnEncoder
import com.github.jasync.sql.db.column.ColumnEncoderRegistry
import com.github.jasync.sql.db.column.DateEncoderDecoder
import com.github.jasync.sql.db.column.DoubleEncoderDecoder
import com.github.jasync.sql.db.column.FloatEncoderDecoder
import com.github.jasync.sql.db.column.InetAddressEncoderDecoder
import com.github.jasync.sql.db.column.IntegerEncoderDecoder
import com.github.jasync.sql.db.column.LongEncoderDecoder
import com.github.jasync.sql.db.column.SQLTimeEncoder
import com.github.jasync.sql.db.column.ShortEncoderDecoder
import com.github.jasync.sql.db.column.StringEncoderDecoder
import com.github.jasync.sql.db.column.TimeEncoderDecoder
import com.github.jasync.sql.db.column.TimestampEncoderDecoder
import com.github.jasync.sql.db.column.TimestampWithTimezoneEncoderDecoder
import com.github.jasync.sql.db.column.UUIDEncoderDecoder
import io.netty.buffer.ByteBuf
import mu.KotlinLogging
import org.threeten.extra.PeriodDuration
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.temporal.TemporalAccessor
import java.util.Collections.addAll

private val logger = KotlinLogging.logger {}

class PostgreSQLColumnEncoderRegistry : ColumnEncoderRegistry {

    companion object {
        val Instance = PostgreSQLColumnEncoderRegistry()
    }

    private val classesSequenceInternal
        get() = listOf(
            Int::class.java to (IntegerEncoderDecoder to ColumnTypes.Integer),
            java.lang.Integer::class.java to (IntegerEncoderDecoder to ColumnTypes.Integer),

            java.lang.Short::class.java to (ShortEncoderDecoder to ColumnTypes.Smallint),
            Short::class.java to (ShortEncoderDecoder to ColumnTypes.Smallint),

            Long::class.java to (LongEncoderDecoder to ColumnTypes.Bigserial),
            java.lang.Long::class.java to (LongEncoderDecoder to ColumnTypes.Bigserial),

            String::class.java to (StringEncoderDecoder to ColumnTypes.Varchar),
            java.lang.String::class.java to (StringEncoderDecoder to ColumnTypes.Varchar),

            Float::class.java to (FloatEncoderDecoder to ColumnTypes.Real),
            java.lang.Float::class.java to (FloatEncoderDecoder to ColumnTypes.Real),

            Double::class.java to (DoubleEncoderDecoder to ColumnTypes.Double),
            java.lang.Double::class.java to (DoubleEncoderDecoder to ColumnTypes.Double),

            BigDecimal::class.java to (BigDecimalEncoderDecoder to ColumnTypes.Numeric),

            java.net.InetAddress::class.java to (InetAddressEncoderDecoder to ColumnTypes.Inet),

            java.util.UUID::class.java to (UUIDEncoderDecoder to ColumnTypes.UUID),

            LocalDate::class.java to (DateEncoderDecoder to ColumnTypes.Date),
            LocalDateTime::class.java to (TimestampEncoderDecoder.Instance to ColumnTypes.Timestamp),
            OffsetDateTime::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            OffsetDateTime::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            Instant::class.java to (DateEncoderDecoder to ColumnTypes.Date),

            PeriodDuration::class.java to (PostgreSQLIntervalEncoderDecoder to ColumnTypes.Interval),
            Period::class.java to (PostgreSQLIntervalEncoderDecoder to ColumnTypes.Interval),
            Duration::class.java to (PostgreSQLIntervalEncoderDecoder to ColumnTypes.Interval),

            java.util.Date::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            java.sql.Date::class.java to (DateEncoderDecoder to ColumnTypes.Date),
            java.sql.Time::class.java to (SQLTimeEncoder to ColumnTypes.Time),
            java.sql.Timestamp::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            java.util.Calendar::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            java.util.GregorianCalendar::class.java to (TimestampWithTimezoneEncoderDecoder to ColumnTypes.TimestampWithTimezone),
            ByteArray::class.java to (ByteArrayEncoderDecoder to ColumnTypes.ByteA),
            ByteBuffer::class.java to (ByteArrayEncoderDecoder to ColumnTypes.ByteA),
            ByteBuf::class.java to (ByteArrayEncoderDecoder to ColumnTypes.ByteA)
        )

    private val classesSequence: MutableList<Pair<Class<out Any>, Pair<ColumnEncoder, Int>>> =
        mutableListOf<Pair<Class<out Any>, Pair<ColumnEncoder, Int>>>(
            LocalTime::class.java to (TimeEncoderDecoder.Instance to ColumnTypes.Time),
            TemporalAccessor::class.java to (TimeEncoderDecoder.Instance to ColumnTypes.Time)
        ).also { it.addAll(classesSequenceInternal) }

    private var classes = classesSequence.toMap()

    /**
     * Add custom encoder
     */
    fun registerEncoder(clazz: Class<out Any>, type: Int, encoder: ColumnEncoder) {
        logger.info { "register encoder $clazz $encoder $type" }
        classesSequence.add(clazz to (encoder to type))
        classes = classesSequence.toMap()
    }

    override fun encode(value: Any?): String? {
        if (value == null) {
            return null
        }

        return encodeValue(value)
    }

    /**
     * Used to encode a value that is not null and not an Option.
     */
    private fun encodeValue(value: Any): String {

        val encoder = this.classes[value.javaClass]

        return if (encoder != null) {
            encoder.first.encode(value)
        } else {
            when (value) {
                is Iterable<*> -> encodeArray(value)
                is Array<*> -> encodeArray(value.asIterable())
                else -> {
                    val found = this.classesSequence.find { entry -> entry.first.isAssignableFrom(value.javaClass) }
                    when {
                        found != null -> found.second.first.encode(value)
                        else -> value.toString()
                    }
                }
            }
        }
    }

    private fun encodeArray(collection: Iterable<*>): String {
        return collection.map { item ->
            if (item == null) {
                "NULL"
            } else {
                if (this.shouldQuote(item)) {
                    "\"" + this.encode(item)!!.replace("\\", """\\""").replace("\"", """\"""") + "\""
                } else {
                    this.encode(item)
                }
            }
        }.joinToString(",", "{", "}")
    }

    private fun shouldQuote(value: Any): Boolean {
        return when (value) {
            is Number -> false
            is Int -> false
            is Short -> false
            is Long -> false
            is Float -> false
            is Double -> false
            is Iterable<*> -> false
            is Array<*> -> false
            else -> true
        }
    }

    override fun kindOf(value: Any?): Int {
        return if (value == null) {
            0
        } else {
            when (value) {
                is String -> ColumnTypes.Untyped
                else -> {
                    val fromClasses = this.classes[value.javaClass]
                    when {
                        fromClasses != null -> fromClasses.second
                        else -> ColumnTypes.Untyped
                    }
                }
            }
        }
    }
}
