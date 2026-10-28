package app.ridemate.data

/**
 * Phone-only enums that have no counterpart in `core/model`. Stored by [name] (TEXT) through [Converters]; renaming
 * an entry is a schema change and needs a migration.
 */

/** §5 Session.status. */
enum class SessionStatus { ACTIVE, CLOSED }

/** §5 Evidence.source — where a "cancelled" proof came from (§4.6). */
enum class EvidenceSource { ONDC, TEMPLATE, REMOVAL_CAPTURE, USER }

/** §5 ActionOutbox.kind. ERASE_SERVER is the queued `/v1/erase` retry (§4.11). */
enum class OutboxKind { REMINDER, FOLLOWUP_CHECK, OPEN_NEXT_APP, ERASE_SERVER }

/** §5 ActionOutbox.status. DEAD rows gave up (too many attempts, or no longer deliverable). */
enum class OutboxStatus { PENDING, DONE, DEAD }
