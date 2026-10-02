package io.packagex.texttemplates.sdk

/**
 * SDK-wide metadata. [VERSION] is the public PXTextTemplates SDK version — stable
 * and independent of the host app's `versionName`. It is stamped into the
 * `report()` payload so backend investigations know which SDK produced a scan.
 * Mirrors iOS `PXTextTemplates.version`.
 */
object PXTextTemplates {
    const val VERSION = "1.0.0"
}
