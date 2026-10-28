package app.ridemate.compare

import app.ridemate.core.config.SanityBandsConfig
import app.ridemate.core.format.RupeeParser
import app.ridemate.core.gate.GatedRow
import app.ridemate.core.model.Provider
import app.ridemate.core.model.VehicleClass
import app.ridemate.util.Clock

/** The outcome of typing a price. */
sealed interface ManualEntryResult {
    data class Saved(val row: GatedRow) : ManualEntryResult

    /** Not a price RideMate understands (`120`, `₹1,234`, `Rs 110–130`, `१२०` are fine). */
    data object NotAPrice : ManualEntryResult

    /** Outside the plausible range; [minInr]–[maxInr] tells the user what is accepted. */
    data class OutOfRange(val minInr: Int, val maxInr: Int) : ManualEntryResult

    /** The leg no longer exists (the comparison was closed or erased). */
    data object NoSuchLeg : ManualEntryResult
}

/**
 * Manual mode (goal 21): the user types the price they see. Stored as USER_ENTERED and always labelled "entered by
 * you"; it is never marked cheapest (§4.3.7). Indian formats are parsed by core [RupeeParser].
 */
class ManualEntryUseCase(
    private val store: QuoteStore,
    private val clock: Clock,
    private val bands: () -> SanityBandsConfig,
) {
    suspend fun enter(legId: String, provider: Provider, vehicleClass: VehicleClass, text: String): ManualEntryResult {
        val amount = RupeeParser.parse(text, requireCurrency = false) ?: return ManualEntryResult.NotAPrice
        val limits = bands()
        if (amount.min < limits.priceMinInr || amount.max > limits.priceMaxInr) {
            return ManualEntryResult.OutOfRange(limits.priceMinInr, limits.priceMaxInr)
        }
        val row = GatedRow.userEntered(provider, vehicleClass, amount.range, clock.wallMs())
        return if (store.saveUserEntered(legId, row)) ManualEntryResult.Saved(row) else ManualEntryResult.NoSuchLeg
    }
}
