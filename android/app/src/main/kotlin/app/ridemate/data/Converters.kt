package app.ridemate.data

import androidx.room.TypeConverter
import app.ridemate.core.model.EtaSource
import app.ridemate.core.model.EvidenceRung
import app.ridemate.core.model.Provider
import app.ridemate.core.model.QuoteStatus
import app.ridemate.core.model.RideState
import app.ridemate.core.model.VehicleClass

/**
 * Every enum column is TEXT holding the enum's `name` (stable across reorders, readable in exported schemas and
 * migrations). Lists are comma-separated names in the order given.
 */
class Converters {
    @TypeConverter fun providerToText(v: Provider): String = v.name
    @TypeConverter fun textToProvider(v: String): Provider = Provider.valueOf(v)

    @TypeConverter fun rideStateToText(v: RideState): String = v.name
    @TypeConverter fun textToRideState(v: String): RideState = RideState.valueOf(v)

    @TypeConverter fun vehicleClassToText(v: VehicleClass): String = v.name
    @TypeConverter fun textToVehicleClass(v: String): VehicleClass = VehicleClass.valueOf(v)

    @TypeConverter fun quoteStatusToText(v: QuoteStatus): String = v.name
    @TypeConverter fun textToQuoteStatus(v: String): QuoteStatus = QuoteStatus.valueOf(v)

    @TypeConverter fun etaSourceToText(v: EtaSource): String = v.name
    @TypeConverter fun textToEtaSource(v: String): EtaSource = EtaSource.valueOf(v)

    @TypeConverter fun evidenceRungToText(v: EvidenceRung): String = v.name
    @TypeConverter fun textToEvidenceRung(v: String): EvidenceRung = EvidenceRung.valueOf(v)

    @TypeConverter fun sessionStatusToText(v: SessionStatus): String = v.name
    @TypeConverter fun textToSessionStatus(v: String): SessionStatus = SessionStatus.valueOf(v)

    @TypeConverter fun evidenceSourceToText(v: EvidenceSource): String = v.name
    @TypeConverter fun textToEvidenceSource(v: String): EvidenceSource = EvidenceSource.valueOf(v)

    @TypeConverter fun outboxKindToText(v: OutboxKind): String = v.name
    @TypeConverter fun textToOutboxKind(v: String): OutboxKind = OutboxKind.valueOf(v)

    @TypeConverter fun outboxStatusToText(v: OutboxStatus): String = v.name
    @TypeConverter fun textToOutboxStatus(v: String): OutboxStatus = OutboxStatus.valueOf(v)

    @TypeConverter fun providersToText(v: List<Provider>): String = v.joinToString(",") { it.name }
    @TypeConverter fun textToProviders(v: String): List<Provider> =
        if (v.isEmpty()) emptyList() else v.split(',').map { Provider.valueOf(it) }

    @TypeConverter fun vehicleClassesToText(v: List<VehicleClass>): String = v.joinToString(",") { it.name }
    @TypeConverter fun textToVehicleClasses(v: String): List<VehicleClass> =
        if (v.isEmpty()) emptyList() else v.split(',').map { VehicleClass.valueOf(it) }
}
