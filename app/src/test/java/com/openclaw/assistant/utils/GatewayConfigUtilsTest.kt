package com.openclaw.assistant.utils

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GatewayConfigUtilsTest {

    // Pre-generated Base64 URL-safe setup codes (python3 base64.urlsafe_b64encode, no padding):
    // {"url": "wss://example.com:18789", "password": "testpass"}
    private val codePasswordOnly =
        "eyJ1cmwiOiAid3NzOi8vZXhhbXBsZS5jb206MTg3ODkiLCAicGFzc3dvcmQiOiAidGVzdHBhc3MifQ"

    // {"url": "wss://example.com:18789", "token": "mytoken", "password": "mypass"}
    private val codeTokenAndPassword =
        "eyJ1cmwiOiAid3NzOi8vZXhhbXBsZS5jb206MTg3ODkiLCAidG9rZW4iOiAibXl0b2tlbiIsICJwYXNzd29yZCI6ICJteXBhc3MifQ"

    // {"url": "ws://192.168.1.100:18789", "password": "localpass"}
    private val codeLocalWsPassword =
        "eyJ1cmwiOiAid3M6Ly8xOTIuMTY4LjEuMTAwOjE4Nzg5IiwgInBhc3N3b3JkIjogImxvY2FscGFzcyJ9"

    // {"url": "wss://example.com:18789", "token": "mytoken"}
    private val codeTokenOnly =
        "eyJ1cmwiOiAid3NzOi8vZXhhbXBsZS5jb206MTg3ODkiLCAidG9rZW4iOiAibXl0b2tlbiJ9"

    // {"url":"wss://example.com:18789","bootstrapToken":"bt_abc123xyz"}  (from `openclaw qr`)
    private val codeBootstrapToken =
        "eyJ1cmwiOiJ3c3M6Ly9leGFtcGxlLmNvbToxODc4OSIsImJvb3RzdHJhcFRva2VuIjoiYnRfYWJjMTIzeHl6In0"

    @Test
    fun `decodeGatewaySetupCode extracts password from WSS setup code`() {
        val result = GatewayConfigUtils.decodeGatewaySetupCode(codePasswordOnly)
        assertNotNull("Setup code should decode successfully", result)
        assertEquals("testpass", result!!.password)
        assertNull("Token should be null when not present", result.token)
        assertEquals("wss://example.com:18789", result.url)
    }

    @Test
    fun `decodeGatewaySetupCode extracts both token and password`() {
        val result = GatewayConfigUtils.decodeGatewaySetupCode(codeTokenAndPassword)
        assertNotNull("Setup code should decode successfully", result)
        assertEquals("mytoken", result!!.token)
        assertEquals("mypass", result.password)
    }

    @Test
    fun `decodeGatewaySetupCode extracts password from local WS setup code`() {
        val result = GatewayConfigUtils.decodeGatewaySetupCode(codeLocalWsPassword)
        assertNotNull("Local WS setup code should decode successfully", result)
        assertEquals("localpass", result!!.password)
        assertNull("Token should be null when not present", result.token)
        assertEquals("ws://192.168.1.100:18789", result.url)
    }

    @Test
    fun `decodeGatewaySetupCode returns null password when only token present`() {
        val result = GatewayConfigUtils.decodeGatewaySetupCode(codeTokenOnly)
        assertNotNull("Setup code should decode successfully", result)
        assertNull("Password should be null when not present", result!!.password)
        assertEquals("mytoken", result.token)
    }

    @Test
    fun `decodeGatewaySetupCode extracts bootstrapToken as separate field`() {
        val result = GatewayConfigUtils.decodeGatewaySetupCode(codeBootstrapToken)
        assertNotNull("Bootstrap token setup code should decode successfully", result)
        assertEquals("bt_abc123xyz", result!!.bootstrapToken)
        assertNull("Token should be null when not explicitly present", result.token)
        assertNull("Password should be null when not explicitly present", result.password)
    }

    @Test
    fun `decodeGatewaySetupCode returns null for blank input`() {
        assertNull(GatewayConfigUtils.decodeGatewaySetupCode(""))
        assertNull(GatewayConfigUtils.decodeGatewaySetupCode("   "))
    }

    @Test
    fun `decodeGatewaySetupCode returns null for invalid base64`() {
        assertNull(GatewayConfigUtils.decodeGatewaySetupCode("not-valid-base64!!!"))
    }

    @Test
    fun `parseGatewayEndpoint extracts WSS host and port`() {
        val result = GatewayConfigUtils.parseGatewayEndpoint("wss://example.com:18789")
        assertNotNull(result)
        assertEquals("example.com", result!!.host)
        assertEquals(18789, result.port)
        assertEquals(true, result.tls)
    }

    @Test
    fun `parseGatewayEndpoint defaults to port 18789 for WS`() {
        val result = GatewayConfigUtils.parseGatewayEndpoint("ws://192.168.1.100")
        assertNotNull(result)
        assertEquals(18789, result!!.port)
        assertEquals(false, result.tls)
    }

    @Test
    fun `parseGatewayEndpoint defaults to port 443 for WSS without port`() {
        val result = GatewayConfigUtils.parseGatewayEndpoint("wss://example.com")
        assertNotNull(result)
        assertEquals(443, result!!.port)
        assertEquals(true, result.tls)
    }
}
