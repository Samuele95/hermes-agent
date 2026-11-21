package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// A connected provider is a row the core holds a credential for; a saved endpoint is listed but deleted in setup.
class ConnectedProviderTest {
    /** One `model.options` provider row as the core writes it. */
    private fun row(json: String) = bridgeJson.decodeFromString(ModelOptionProvider.serializer(), json)

    @Test
    fun aSignedInRowIsConnectedAndRemovable() {
        assertEquals(ConnectedProvider("nous", "Nous Portal", removable = true, current = true),
                     ConnectedProvider.of(row("""{"slug":"nous","name":"Nous Portal","models":["nous/hermes-4"],
                                                 "authenticated":true,"is_current":true}""")))
        assertEquals(false, ConnectedProvider.of(row("""{"slug":"nous","name":"Nous Portal","models":["nous/hermes-4"],
                                                         "authenticated":true}"""))?.current)
    }

    @Test
    fun rowsWithoutACredentialAreNotConnected() {
        val none = listOf(
            """{"slug":"deepseek","name":"DeepSeek","models":[],"authenticated":false,"auth_type":"api_key","key_env":"DEEPSEEK_API_KEY"}""",
            """{"slug":"deepseek","name":"DeepSeek","models":[]}""",  // authenticated absent
            """{"slug":"nous","name":"Nous · free tier","models":["m"],"authenticated":true,"free_tier_row":true}""",
            """{"slug":"llamacpp","name":"On this phone","models":["m"],"authenticated":true,"auth_type":"local"}""",
            """{"slug":"moa","name":"Mixture of agents","models":["m"],"authenticated":true,"auth_type":"virtual"}""",
        )
        for (json in none) assertNull(json, ConnectedProvider.of(row(json)))
    }

    @Test
    fun aSavedEndpointIsConnectedButRemovedOnlyInSetup() {
        val endpoint = ConnectedProvider.of(row("""{"slug":"dgx","name":"DGX","authenticated":true,"is_user_defined":true,
                                                   "api_url":"http://spark-98d1:11434/v1"}"""))

        assertEquals("dgx", endpoint?.slug)
        assertFalse(endpoint!!.removable)
    }
}
