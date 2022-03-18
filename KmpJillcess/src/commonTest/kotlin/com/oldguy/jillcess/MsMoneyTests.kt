package com.oldguy.jillcess

import com.oldguy.jillcess.implementations.Jet
import com.oldguy.jillcess.DatabaseTestBaseHelp.Companion.wrongPassword
import com.oldguy.jillcess.MoneyTestBaseHelp.Companion.tableNames2001
import com.oldguy.jillcess.MoneyTestBaseHelp.Companion.tableNames2002
import com.oldguy.jillcess.MoneyTestBaseHelp.Companion.tableNames2008
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@ExperimentalCoroutinesApi
class MoneyTests {
    @Test
    fun money2001Test() {
        runTest {
            MoneyTestBaseHelp("money2001.mny").let { help ->
                help.openException?.let {
                    println(it.message)
                }
                assertTrue(help.db.isOpen)
                assertEquals(Jet.Version.Access2000_2002_2003, help.db.jet.version)
                assertContentEquals(tableNames2001, help.db.tableNameList())
                help.verifyCurrencyTable()
            }
        }
    }

    @Test
    fun money2001PwdTest() {
        runTest {
            MoneyTestBaseHelp("money2001-pwd.mny").let {
                assertTrue(it.db.isOpen)
                assertEquals(Jet.Version.Access2000_2002_2003, it.db.jet.version)
                assertContentEquals(tableNames2001, it.db.tableNameList())
                it.verifyCurrencyTable()
            }
        }
    }

    @Test
    fun money2002Test() {
        runTest {
            MoneyTestBaseHelp("money2002.mny").let {
                assertTrue(it.db.isOpen)
                assertEquals(Jet.Version.Access2000_2002_2003, it.db.jet.version)
                assertContentEquals(tableNames2002(), it.db.tableNameList())
                it.verifyCurrencyTable()
            }
        }
    }

    @Test
    fun money2008Test() {
        runTest {
            MoneyTestBaseHelp("money2008.mny").let {
                assertTrue(it.db.isOpen)
                assertEquals(Jet.Version.Access2000_2002_2003, it.db.jet.version)
                assertContentEquals(tableNames2008(), it.db.tableNameList())
                it.verifyCurrencyTable(true)
            }
        }
    }

    @Test
    fun money2008NoPasswordTest() {
        runTest {
            MoneyTestBaseHelp("money2008-pwd.mny").let {
                assertNotNull(it.openException)
                assertFalse(it.db.isOpen)
                assertTrue(it.openException?.message?.startsWith(wrongPassword) ?: false)
            }
        }
    }

    @Test
    fun money2008BadPasswordTest() {
        runTest {
            MoneyTestBaseHelp("money2008-pwd.mny", "WrongPassword").let {
                assertNotNull(it.openException)
                assertFalse(it.db.isOpen)
                assertTrue(it.openException?.message?.startsWith(wrongPassword) ?: false)
            }
        }
    }

    @Test
    fun money2008PasswordTest() {
        runTest {
            MoneyTestBaseHelp("money2008-pwd.mny", "Test12345").let {
                assertNull(it.openException)
                assertTrue(it.db.isOpen)
                assertEquals(Jet.Version.Access2000_2002_2003, it.db.jet.version)
                assertContentEquals(tableNames2008(), it.db.tableNameList())
                it.verifyCurrencyTable(true)
            }
        }
    }
}
