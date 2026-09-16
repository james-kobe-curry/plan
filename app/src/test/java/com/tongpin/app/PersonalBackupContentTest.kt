package com.tongpin.app

import org.junit.Assert.*
import org.junit.Test

class PersonalBackupContentTest {
    @Test fun personalContentIsProtectedEvenBeforeTheFirstTask() {
        assertFalse(hasBackupContent(AppData()))
        assertTrue(hasBackupContent(AppData(categories = listOf(CustomCategory(name = "语言")))))
        assertTrue(hasBackupContent(AppData(profile = PersonalProfile(motto = "每天一点积累"))))
        assertTrue(hasBackupContent(AppData(profile = PersonalProfile(avatarId = "LEAF"))))
        assertFalse(hasBackupContent(AppData(nickname = "新名字")))
    }
}
