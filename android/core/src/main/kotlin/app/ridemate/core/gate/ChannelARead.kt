package app.ridemate.core.gate

import app.ridemate.core.model.Provider
import app.ridemate.core.model.ScreenType
import app.ridemate.core.model.VehicleClass
import app.ridemate.core.ocr.GridBox
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Channel A's read of one masked frame (contracts §1.3). Every field is nullable except [options]. Enum values
 * this build doesn't know decode to null instead of failing the whole read, so the gate simply can't verify them.
 */
@Serializable
data class ChannelARead(
    @Serializable(with = LenientProvider::class) val app: Provider? = null,
    @SerialName("screen_type") @Serializable(with = LenientScreenType::class) val screenType: ScreenType? = null,
    val options: List<ChannelAOption> = emptyList(),
    @SerialName("fee_text") val feeText: String? = null,
    @SerialName("driver_eta_min") val driverEtaMin: Int? = null,
    @SerialName("driver_distance_text") val driverDistanceText: String? = null,
) {
    fun toJson(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        /** At most this many options are considered; extra ones are truncated (contracts §1.3). */
        const val MAX_OPTIONS = 12

        /** Parses a read; throws on malformed JSON or wrongly typed fields. */
        fun fromJson(raw: String): ChannelARead = WireJson.decodeFromString(serializer(), raw).truncated()

        fun fromJsonOrNull(raw: String): ChannelARead? = runCatching { fromJson(raw) }.getOrNull()
    }

    internal fun truncated(): ChannelARead = if (options.size <= MAX_OPTIONS) this else copy(options = options.take(MAX_OPTIONS))
}

@Serializable
data class ChannelAOption(
    @SerialName("vehicle_class") @Serializable(with = LenientVehicleClass::class) val vehicleClass: VehicleClass? = null,
    @SerialName("label_text") val labelText: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
    @SerialName("price_struck_text") val priceStruckText: String? = null,
    @SerialName("eta_min") val etaMin: Int? = null,
    @SerialName("bbox_0_1000") @Serializable(with = GridListSerializer::class) val bbox: List<Double>? = null,
) {
    /** The row's box on the 0–1000 grid; null unless the model gave four finite numbers. */
    val gridBox: GridBox? get() = GridBox.fromList(bbox)
}

/** `/v1/read-screen` 200 body. */
@Serializable
data class ReadScreenResponse(
    val read: ChannelARead,
    val source: String? = null,
    val model: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("request_id") val requestId: String? = null,
) {
    companion object {
        fun fromJson(raw: String): ReadScreenResponse =
            WireJson.decodeFromString(serializer(), raw).let { it.copy(read = it.read.truncated()) }
    }
}

/** `/v1/reread` multipart `meta`. */
@Serializable
data class RereadMeta(val app: String, val rows: List<RereadRowRef>) {
    fun toJson(): String = WireJson.encodeToString(serializer(), this)
}

@Serializable
data class RereadRowRef(@SerialName("row_id") val rowId: String)

/** `/v1/reread` 200 body. */
@Serializable
data class RereadResponse(
    val rows: List<RereadRow> = emptyList(),
    val source: String? = null,
    @SerialName("request_id") val requestId: String? = null,
) {
    companion object {
        fun fromJson(raw: String): RereadResponse = WireJson.decodeFromString(serializer(), raw)
    }
}

@Serializable
data class RereadRow(
    @SerialName("row_id") val rowId: String,
    @SerialName("vehicle_class") @Serializable(with = LenientVehicleClass::class) val vehicleClass: VehicleClass? = null,
    @SerialName("label_text") val labelText: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
    @SerialName("eta_min") val etaMin: Int? = null,
)

internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Decodes a known wire string to its enum and anything else (unknown string, number, object) to null. */
@OptIn(ExperimentalSerializationApi::class)
internal abstract class LenientEnumSerializer<E : Enum<E>>(
    name: String,
    private val wireName: (E) -> String,
    values: List<E>,
) : KSerializer<E?> {
    private val byWire: Map<String, E> = values.associateBy(wireName)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING).nullable

    override fun serialize(encoder: Encoder, value: E?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(wireName(value))
    }

    override fun deserialize(decoder: Decoder): E? {
        if (decoder !is JsonDecoder) return byWire[decoder.decodeString()]
        val element = decoder.decodeJsonElement()
        return if (element is JsonPrimitive && element.isString) byWire[element.content] else null
    }
}

internal object LenientVehicleClass :
    LenientEnumSerializer<VehicleClass>("ridemate.LenientVehicleClass", { it.wireId }, VehicleClass.entries)

internal object LenientScreenType : LenientEnumSerializer<ScreenType>(
    "ridemate.LenientScreenType",
    { ScreenType.serializer().descriptor.getElementName(it.ordinal) },
    ScreenType.entries,
)

internal object LenientProvider : LenientEnumSerializer<Provider>("ridemate.LenientProvider", { it.wireId }, Provider.entries)

/** `bbox_0_1000` read as numbers (integers or decimals); anything else decodes to null. Whole numbers encode as ints. */
@OptIn(ExperimentalSerializationApi::class)
internal object GridListSerializer : KSerializer<List<Double>?> {
    private val list = ListSerializer(Double.serializer())
    override val descriptor: SerialDescriptor = list.descriptor.nullable

    override fun serialize(encoder: Encoder, value: List<Double>?) {
        when {
            value == null -> encoder.encodeNull()
            encoder is JsonEncoder -> encoder.encodeJsonElement(
                JsonArray(value.map { if (it % 1.0 == 0.0 && it.isFinite()) JsonPrimitive(it.toLong()) else JsonPrimitive(it) }),
            )
            else -> encoder.encodeSerializableValue(list, value)
        }
    }

    override fun deserialize(decoder: Decoder): List<Double>? {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(list)
        val element = decoder.decodeJsonElement() as? JsonArray ?: return null
        val values = element.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return null }
        return values
    }
}
