package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.BillingBlock
import com.nousresearch.hermes.bridge.protocol.Events
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The banner is mapped once from the core's billing block: its name, the page to open, its first line.
class BillingTest {
    /**
     * One billing block as `prompt_turn.py` writes it into `message.complete`, read as the event wire reads it: an
     * absent `billing_url` is null there (`explicitNulls = false`), where `bridgeJson` would refuse the block.
     */
    private fun block(json: String): BillingBlock =
        CoreEvent("message.complete", "s1", Json.parseToJsonElement("""{"billing":$json}""")).decode(Events.MESSAGE_COMPLETE)!!.billing!!

    private fun nous(message: String) = block("""{"provider":"nous","provider_label":"Nous Portal","model":"m",
        "billing_url":"https://portal.nousresearch.com/billing","is_nous":true,"message":"$message"}""")

    /** A provider's block with [url] as its `billing_url` field (the whole `"billing_url":…` pair, or none). */
    private fun linked(url: String, label: String = "Acme") =
        block("""{"provider":"acme","provider_label":"$label","model":"m",$url"is_nous":false,"message":"Out."}""")

    @Test
    fun aProvidersWallNamesItsLabelPageAndFirstLine() {
        val wall = BillingWall.of(block("""{"provider":"deepseek","provider_label":"DeepSeek","model":"deepseek-v4-pro",
            "billing_url":"https://platform.deepseek.com/top_up","is_nous":false,
            "message":"DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro.\nAdd credits or update billing with that provider, then retry.\nYou can switch providers temporarily with /model <model> --provider <provider>."}"""))

        assertEquals(BillingWall("deepseek", "DeepSeek", false, "https://platform.deepseek.com/top_up",
                                 "DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro."), wall)
    }

    @Test
    fun aNousWallWithoutWordsOpensThePortalAndSaysTheFallback() {
        val wall = BillingWall.of(nous(""))

        assertTrue(wall.nous)
        assertEquals("https://portal.nousresearch.com/billing", wall.url)
        assertEquals(BillingWall.FALLBACK, wall.message)
        assertEquals("blank lines are no words", BillingWall.FALLBACK, BillingWall.of(nous("\\n  \\n")).message)
    }

    @Test
    fun aLinkTheAppCannotOpenIsNone() {
        for (url in listOf(""""billing_url":null,""", "", """"billing_url":"  ",""", """"billing_url":"javascript:alert(1)",""",
                           """"billing_url":"intent://x#Intent;end",""")) {
            assertNull(url, BillingWall.of(linked(url)).url)
        }
        assertEquals("https://x.test/billing", BillingWall.of(linked(""""billing_url":" https://x.test/billing ",""")).url)
        assertEquals("a blank label reads as the provider", "acme", BillingWall.of(linked("", label = " ")).label)
    }
}
