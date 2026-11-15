package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.BillingBlock

/**
 * The core's billing wall for the chat's last turn: the provider's account is out of credits. An immutable copy of
 * the core's `BillingBlock` taken at the completion (EJ-17, batch-3 F7), so no screen holds a banner state that could
 * outlive the session switch that must clear it. [provider] is the core's slug and [label] its readable name; [nous]:
 * the failing route is Nous's. [url] is a page to open in the browser, or null: none the app can open (D2, D3).
 * [message] is the first line of the core's guidance, else [FALLBACK] (D4). Raised per completion and never stored (§2.5).
 */
data class BillingWall(val provider: String, val label: String, val nous: Boolean, val url: String?, val message: String) {
    companion object {
        /** Desktop's words when the core gives none (`en_billing.ts` `fallbackMessage`). */
        const val FALLBACK = "Your account is out of credits. Add credits to keep going."
    }
}

/**
 * The core's [block], read once: names trimmed (a blank label reads as the provider), a `billing_url` kept only when it
 * is a web page (D3: an `ACTION_VIEW` never gets an `intent:` or `file:` URI), and the first non-blank line of its
 * message: the others are CLI hints (D4). `model` and `unverified` are not read. Total.
 */
fun BillingWall.Companion.of(block: BillingBlock): BillingWall {
    val provider = block.provider.trim()
    return BillingWall(
        provider = provider,
        label = block.providerLabel.trim().ifEmpty { provider },
        nous = block.isNous,
        url = block.billingUrl?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        message = block.message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: BillingWall.FALLBACK,
    )
}
