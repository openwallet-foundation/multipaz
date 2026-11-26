package org.multipaz.openid4vci.request

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRequestTest {
    @Test
    fun acceptsJsonMediaTypeWithWhitespaceAndParameters() {
        assertTrue(isJsonNotificationContentType("application/json ; charset=utf-8"))
        assertTrue(isJsonNotificationContentType("APPLICATION/JSON"))
        assertFalse(isJsonNotificationContentType("text/plain"))
        assertFalse(isJsonNotificationContentType(null))
    }
}
