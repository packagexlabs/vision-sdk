package io.packagex.visiondemo.data
import org.junit.Assert.*
import org.junit.Test
class SecretsTest {
    @Test fun missingKeyIsReported() {
        val s = Secrets(apiKey = "", environment = "staging")
        assertTrue(s.isMissing)
        assertEquals("Add STAGING_API_KEY to secrets.properties", s.missingMessage)
    }
    @Test fun presentKeyIsNotMissing() = assertFalse(Secrets("key_x", "production").isMissing)
    @Test fun pickSelectsKeyForEnvironment() {
        assertEquals("p", Secrets.pick("production", staging = "s", production = "p").apiKey)
        assertEquals("s", Secrets.pick("staging", staging = "s", production = "p").apiKey)
    }
}
