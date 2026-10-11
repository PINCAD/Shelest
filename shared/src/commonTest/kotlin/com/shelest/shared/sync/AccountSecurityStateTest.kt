package com.shelest.shared.sync

import kotlin.test.*

class AccountSecurityStateTest {
    @Test fun securityFlagSurvivesNormalisationAndPersistence() {
        val account = SyncProtocol.account("""{"id":"test","hasPassword":true,"twoFactorEnabled":true}""")
        assertTrue(account.twoFactorEnabled)
        assertTrue(SyncProtocol.account(account.toJson()).twoFactorEnabled)
    }
    @Test fun legacyAccountsRemainCompatible() {
        assertFalse(SyncProtocol.account("""{"id":"legacy"}""").twoFactorEnabled)
    }
}
