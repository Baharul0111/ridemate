package app.ridemate.ondc

import android.content.Context
import androidx.annotation.StringRes
import app.ridemate.R
import app.ridemate.core.format.IndianFormat
import app.ridemate.core.model.VehicleClass

/** User-facing wording for the ONDC row and sheet (strings_ondc.xml, English + Hindi); every failure says what to do next. */
object OndcMessages {

    /** The quote's labels: always "network quote", plus "ONDC demo seller" for the hackathon's mock seller. */
    fun quoteLabel(context: Context, quote: OndcQuoteView): String {
        val network = context.getString(R.string.ondc_label_network_quote)
        return if (quote.demoSeller) context.getString(R.string.ondc_row_label, network, context.getString(R.string.ondc_label_demo_seller)) else network
    }

    /** Screen-reader summary of a quote row: seller, class, price, ETA and its labels. */
    fun quoteDescription(context: Context, quote: OndcQuoteView, className: String): String {
        val row = quote.row
        val price = row.price?.let { IndianFormat.rupeeRange(it) }.orEmpty()
        val eta = row.eta?.let { context.getString(R.string.ondc_eta, it) } ?: context.getString(R.string.ondc_eta_unknown)
        val seller = row.displayLabel.ifBlank { context.getString(R.string.ondc_name) }
        return context.getString(R.string.ondc_row_description, seller, className, price, eta, quoteLabel(context, quote))
    }

    @StringRes
    fun unavailableRes(reason: OndcUnavailableReason): Int = when (reason) {
        OndcUnavailableReason.SWITCHED_OFF -> R.string.ondc_unavailable_switched_off
        OndcUnavailableReason.SIGN_IN_NEEDED -> R.string.ondc_unavailable_sign_in
        OndcUnavailableReason.OFFLINE -> R.string.ondc_unavailable_offline
        OndcUnavailableReason.TIMEOUT -> R.string.ondc_unavailable_timeout
        OndcUnavailableReason.SERVER -> R.string.ondc_unavailable_server
        OndcUnavailableReason.NOT_CONFIGURED -> R.string.ondc_unavailable_not_configured
        OndcUnavailableReason.LISTENER_FAILED -> R.string.ondc_unavailable_listener
        OndcUnavailableReason.QUOTA -> R.string.ondc_unavailable_quota
        OndcUnavailableReason.CANCEL_NO_ANSWER -> R.string.ondc_unavailable_cancel_no_answer
        OndcUnavailableReason.CANCEL_UNCONFIRMED -> R.string.ondc_unavailable_cancel_unconfirmed
    }

    /** The status line under the ONDC row, or null when quotes speak for themselves. */
    fun status(context: Context, view: OndcView): String? = when (view.phase) {
        OndcPhase.IDLE, OndcPhase.QUOTES -> null
        OndcPhase.SEARCHING -> context.getString(R.string.ondc_searching)
        OndcPhase.NO_RESULTS -> context.getString(R.string.ondc_no_results)
        OndcPhase.BOOKING -> context.getString(R.string.ondc_booking)
        OndcPhase.BOOKED -> context.getString(R.string.ondc_booked)
        OndcPhase.ARRIVING -> context.getString(R.string.ondc_arriving)
        OndcPhase.ON_TRIP -> context.getString(R.string.ondc_on_trip)
        OndcPhase.ENDED -> context.getString(R.string.ondc_ended)
        OndcPhase.CANCEL_REQUESTED -> context.getString(R.string.ondc_cancel_requested)
        OndcPhase.CANCEL_FEE_SHOWN -> feeText(context, view.feeInr)
        OndcPhase.CANCELLING -> context.getString(R.string.ondc_cancelling)
        OndcPhase.CANCELLED -> context.getString(R.string.ondc_cancelled)
        OndcPhase.FAILED -> context.getString(R.string.ondc_failed)
        OndcPhase.UNAVAILABLE -> context.getString(
            R.string.ondc_unavailable_line,
            context.getString(R.string.ondc_unavailable),
            context.getString(unavailableRes(view.unavailable ?: OndcUnavailableReason.SERVER)),
        )
    }

    /** "Cancelling now costs ₹X (the network's fee)…" or "Cancelling now is free…", shown before the user confirms. */
    fun feeText(context: Context, feeInr: Int?): String = when {
        feeInr == null -> context.getString(R.string.ondc_cancel_requested)
        feeInr == 0 -> context.getString(R.string.ondc_cancel_free)
        else -> context.getString(R.string.ondc_cancel_fee, IndianFormat.group(feeInr.toLong()))
    }

    /** Vehicle class names for ONDC rows. */
    @StringRes
    fun classRes(vehicleClass: VehicleClass): Int = when (vehicleClass) {
        VehicleClass.BIKE -> R.string.demo_class_bike
        VehicleClass.AUTO -> R.string.demo_class_auto
        VehicleClass.CAB_ECONOMY -> R.string.demo_class_cab_economy
        VehicleClass.CAB_PREMIUM -> R.string.demo_class_cab_premium
        VehicleClass.CAB_XL -> R.string.demo_class_cab_xl
        VehicleClass.OTHER -> R.string.demo_class_other
    }
}
