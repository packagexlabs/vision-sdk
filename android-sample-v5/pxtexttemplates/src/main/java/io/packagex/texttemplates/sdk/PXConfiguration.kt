package io.packagex.texttemplates.sdk

/**
 * Immutable configuration for a [PXClient]. Mirrors the iOS SDK's
 * `PXConfiguration`.
 *
 * The prediction API base URL is NOT configured here — it is baked in at build
 * time (`BuildConfig.PX_BASE_URL`, overridable via the `PX_BASE_URL` env var or a
 * `-PpxBaseUrl=` Gradle property) so the shipped SDK points at production.
 *
 * @param apiKey   sent as the `X-API-Key` header on every request. Empty
 *                 disables the header (for a local backend with auth off).
 * @param userEmail sent as the `X-User-Email` header. Null/empty omits it.
 */
data class PXConfiguration(
    val apiKey: String,
    val userEmail: String? = null,
)

/**
 * Thrown by [PXClient] suspend calls when a network/parse step fails, or when a
 * precondition is violated (e.g. predicting with no templates loaded).
 *
 * [code] is a **non-null** stable machine-readable identifier (defaults to
 * `"unknown"`) — one of the [PXErrorCode] wire strings. Prefer the typed
 * [errorCode] for exhaustive `when` branching; use [code] for the raw wire value
 * (e.g. bridging to RN/Flutter). Construct with a [PXErrorCode] via the secondary
 * constructor so call sites stay symbolic.
 */
class PXException(
    message: String,
    val code: String = PXErrorCode.UNKNOWN.code,
) : Exception(message) {
    /** Symbolic constructor — preferred over passing a raw string. */
    constructor(message: String, errorCode: PXErrorCode) : this(message, errorCode.code)

    /** Typed view of [code] for exhaustive `when`; [PXErrorCode.UNKNOWN] if the
     *  raw code isn't recognized. Never null. */
    val errorCode: PXErrorCode get() = PXErrorCode.from(code)
}
