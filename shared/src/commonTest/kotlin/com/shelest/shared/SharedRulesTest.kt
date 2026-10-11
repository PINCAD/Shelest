package com.shelest.shared

import kotlin.test.*

class SharedRulesTest {
    @Test fun deletedCopiesCannotPublishUntilExplicitRestore() {
        assertTrue(SyncPolicy.blocksBook(false, false, true, false))
        assertFalse(SyncPolicy.blocksBook(false, false, true, true))
        assertTrue(SyncPolicy.blocksBook(false, true, false, true))
        assertTrue(SyncPolicy.blocksBook(true, false, false, true))
    }

    @Test fun signedOutOrDisabledCannotPublish() {
        assertTrue(SyncPolicy.DEFAULT_AUTO_SYNC)
        assertTrue(SyncPolicy.canPublishPosition(true, true))
        assertFalse(SyncPolicy.canPublishPosition(false, true))
        assertFalse(SyncPolicy.canPublishPosition(true, false))
    }

    @Test fun badQuotaNeverProducesAnInvalidProgressBar() {
        assertEquals(0.0, AccountPresentation.storageFraction(100.0, 0.0))
        assertEquals(0.0, AccountPresentation.storageFraction(Double.NaN, 100.0))
        assertEquals(1.0, AccountPresentation.storageFraction(200.0, 100.0))
        assertEquals(0.5, AccountPresentation.storageFraction(50.0, 100.0))
        assertEquals("1 ГБ", AccountPresentation.formatBytes(1024.0 * 1024 * 1024))
    }

    @Test fun registrationValidatesConfirmationButLoginAcceptsExistingPassword() {
        assertEquals("", AuthForm.validate("reader", "older", "", false))
        assertNotEquals("", AuthForm.validate("reader", "older", "older", true))
        assertNotEquals("", AuthForm.validate("bad login", "long-password", "long-password", true))
        assertNotEquals("", AuthForm.validate("reader", "long-password", "different", true))
        assertEquals("", AuthForm.validate("reader", "long-password", "long-password", true))
    }

    @Test fun accountAvatarHandlesMissingNameAndMultipleWords() {
        assertEquals("Ш", AccountPresentation.initials("  "))
        assertEquals("ЛН", AccountPresentation.initials(" Леон  Некрасов "))
    }
}
