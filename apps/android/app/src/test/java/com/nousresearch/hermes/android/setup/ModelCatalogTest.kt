package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.protocol.ConfigSetResult
import com.nousresearch.hermes.bridge.protocol.ModelOptionsResult
import org.junit.Assert.assertEquals
import org.junit.Test

// The chat's model picker reads one list across every signed-in provider from one `model.options` answer (models-08).
class ModelCatalogTest {
    companion object {
        const val NOW = 1_794_729_600_000L  // 2026-11-15T08:00:00Z

        /** The core's `model.options` answer: Copilot (signed in), Nous (not), DeepSeek (signed in), in that order. */
        fun options(model: String = "gpt-5", provider: String = "copilot", copilot: String = ""): ModelOptionsResult =
            bridgeJson.decodeFromString(ModelOptionsResult.serializer(), """{"model":"$model","provider":"$provider","providers":[
                {"slug":"copilot","name":"GitHub Copilot","authenticated":true,"models":["gpt-5","gpt-5-pro"],
                 "unavailable_models":["gpt-5-pro"],"pricing":{"gpt-5":{"input":"$1.25","output":"$10.00","free":false}}$copilot},
                {"slug":"nous","name":"Nous Portal","authenticated":false,"models":["nous/hermes-4"]},
                {"slug":"deepseek","name":"DeepSeek","authenticated":true,"models":["deepseek-v4-pro","deepseek-v4-flash"],
                 "limit":{"scope":"models","models":{"deepseek-v4-flash":"2026-11-15T09:00:00Z"}}}]}""")

        fun catalog(options: ModelOptionsResult = options(), at: CatalogAt = CatalogAt(NOW)) = ModelCatalog.of(options, at)

        val GPT5 = ModelRow("copilot", "GitHub Copilot", "gpt-5", ModelFacts(input = "$1.25", output = "$10.00"), current = true)
        val GPT5_PRO = ModelRow("copilot", "GitHub Copilot", "gpt-5-pro", ModelFacts(locked = true))
        val V4_PRO = ModelRow("deepseek", "DeepSeek", "deepseek-v4-pro")
        val V4_FLASH = ModelRow("deepseek", "DeepSeek", "deepseek-v4-flash", ModelFacts(resetsAt = "2026-11-15T09:00:00Z"))
    }

    private fun result(json: String) = bridgeJson.decodeFromString(ConfigSetResult.serializer(), json)

    @Test
    fun oneListOfEverySignedInProvidersModelsTheCurrentProviderFirst() {
        assertEquals(ModelCatalog(listOf(GPT5, GPT5_PRO, V4_PRO, V4_FLASH)), catalog())

        val onDeepSeek = options(model = "deepseek-v4-pro", provider = "deepseek")
        assertEquals("DeepSeek first, its current model marked; Nous is not signed in",
                     ModelCatalog(listOf(V4_PRO.copy(current = true), V4_FLASH, GPT5.copy(current = false), GPT5_PRO)),
                     catalog(onDeepSeek))

        val withCustom = catalog(onDeepSeek, CatalogAt(NOW, mapOf("deepseek" to listOf("my-model", "deepseek-v4-pro"))))
        assertEquals("an id the user added follows the provider's own, and one it lists shows once",
                     listOf(V4_PRO.copy(current = true), V4_FLASH, ModelRow("deepseek", "DeepSeek", "my-model", custom = true),
                            GPT5.copy(current = false), GPT5_PRO),
                     withCustom.rows)
    }

    @Test
    fun theCurrentRowIsTheCoresPairByItsSlugNameOrAlias() {
        fun current(options: ModelOptionsResult) = catalog(options).rows.filter { it.current }.map { it.key }

        assertEquals(listOf("copilot:gpt-5"), current(options(provider = "copilot")))
        assertEquals(listOf("copilot:gpt-5"), current(options(provider = "GitHub Copilot")))
        assertEquals(listOf("copilot:gpt-5"), current(options(provider = "gh", copilot = ""","aliases":["gh"]""")))
        assertEquals("no pair named: the core's own mark", listOf("copilot:gpt-5"),
                     current(options(provider = "", copilot = ""","is_current":true""")))
        assertEquals("a model no provider lists", emptyList<String>(), current(options(model = "gpt-6")))
        assertEquals("the same id under another provider is not the current one", emptyList<String>(),
                     current(options(model = "deepseek-v4-pro", provider = "copilot")))
    }

    @Test
    fun aSearchFiltersAcrossProvidersByModelProviderNameOrSlug() {
        val all = catalog()
        assertEquals(listOf("deepseek:deepseek-v4-flash"), all.filtered("flash").map { it.key })
        assertEquals("the provider's name finds all its models", listOf(GPT5, GPT5_PRO), all.filtered("COPILOT"))
        assertEquals(listOf(V4_PRO, V4_FLASH), all.filtered("Deep"))
        assertEquals(listOf(GPT5, GPT5_PRO), all.filtered("gpt"))
        val bySlug = ModelRow("gh", "GitHub Copilot", "gpt-5")
        assertEquals("the slug alone finds them too", listOf(bySlug), ModelCatalog(listOf(bySlug, V4_PRO)).filtered("GH"))
        assertEquals(all.rows, all.filtered(""))
        assertEquals(all.rows, all.filtered("  "))
        assertEquals(emptyList<ModelRow>(), all.filtered("zzz"))
    }

    @Test
    fun aTypedIdIsOfferedOncePerProviderUnlessOneListsIt() {
        val all = catalog()
        assertEquals("the chat's provider first",
                     listOf(ModelRow("copilot", "GitHub Copilot", "openai/gpt-6", custom = true),
                            ModelRow("deepseek", "DeepSeek", "openai/gpt-6", custom = true)),
                     all.typed(" openai/gpt-6 "))
        assertEquals("a provider lists it: the search shows that row", emptyList<ModelRow>(), all.typed("GPT-5"))
        assertEquals(emptyList<ModelRow>(), all.typed("a b"))
        assertEquals(emptyList<ModelRow>(), all.typed(""))
    }

    @Test
    fun theCoresAnswerReadsAsNowNextOrConfirm() {
        assertEquals(Switched.Now("gpt-5"), Switched.of(result("""{"key":"model","value":"gpt-5","scope":"session"}"""), "asked"))
        assertEquals(Switched.Next("gpt-5"), Switched.of(result("""{"key":"model","value":"gpt-5","deferred":true}"""), "asked"))
        assertEquals(Switched.Confirm("gpt-5", "gpt-5 costs more"),
                     Switched.of(result("""{"key":"model","value":"gpt-5","confirm_required":true,"confirm_message":" gpt-5 costs more "}"""), "gpt-5"))
        assertEquals(Switched.Now("asked"), Switched.of(result("""{"key":"model"}"""), "asked"))
        assertEquals("a blank value is the model asked for", Switched.Next("asked"),
                     Switched.of(result("""{"key":"model","value":" ","deferred":true}"""), "asked"))
        assertEquals("a value that is not a string is the model asked for", Switched.Now("asked"),
                     Switched.of(result("""{"key":"model","value":true}"""), "asked"))
    }

    @Test
    fun aRowsFactsAreTheCoresAsOfTheCatalogsClock() {
        val rows = catalog(at = CatalogAt(NOW, mapOf("copilot" to listOf("mine")))).rows.associateBy { it.model }
        assertEquals(ModelFacts(input = "$1.25", output = "$10.00"), rows.getValue("gpt-5").facts)
        assertEquals(ModelFacts(locked = true), rows.getValue("gpt-5-pro").facts)
        assertEquals(ModelFacts(resetsAt = "2026-11-15T09:00:00Z"), rows.getValue("deepseek-v4-flash").facts)
        assertEquals(ModelFacts(), rows.getValue("mine").facts)

        val later = catalog(at = CatalogAt(NOW + 2 * 3_600_000L)).rows.associateBy { it.model }
        assertEquals("a reset already past is none", ModelFacts(), later.getValue("deepseek-v4-flash").facts)
    }
}
