package com.oldguy.jillcess

import com.oldguy.jillcess.cryptography.AgileCodec
import com.oldguy.jillcess.cryptography.OfficeRC4Codec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.*

open class JetTestBaseHelp(fileName: String, password: String = "", subDir: String = "") :
    DatabaseTestBaseHelp(fileName, password, subDir) {
    suspend fun verifyTable1() {
        val t = db.table("Table1")
        val rowCount = t.retrieveAll { rowCount, row ->
            when (rowCount) {
                1 -> {
                    assertEquals(1, row.requireInt("ID"))
                    assertEquals("hello", row.requireString("col1"))
                    assertEquals(0, row.requireInt("col2"))
                }
                2 -> {
                    assertEquals(2, row.requireInt("ID"))
                    assertEquals("world", row.requireString("col1"))
                    assertEquals(42, row.requireInt("col2"))
                }
                else -> {
                }
            }
        }
        assertEquals(2, rowCount)
    }

    suspend fun verifyOfficeTable1() {
        val t = db.table("Table1")
        val rowCount = t.retrieveAll { rowCount, row ->
            when (rowCount) {
                1 -> {
                    assertEquals(1, row.requireInt("ID"))
                    assertEquals("foo", row.requireString("Field1"))
                }
                else -> {
                }
            }
        }
        assertEquals(1, rowCount)
    }

    suspend fun verifyCustomerTable() {
        val t = db.table("Customers")
        val rowCount = t.retrieveAll { rowCount, row ->
            when (rowCount) {
                1 -> {
                    assertEquals(1, row.requireInt("ID"))
                    assertEquals("Test", row.requireString("Field1"))
                }
                2 -> {
                    assertEquals(2, row.requireInt("ID"))
                    assertEquals("Test2", row.requireString("Field1"))
                }
                3 -> {
                    assertEquals(3, row.requireInt("ID"))
                    assertEquals("a", row.requireString("Field1"))
                }
                4 -> {
                    assertEquals(4, row.requireInt("ID"))
                    assertTrue(row.isNull("Field1"))
                }
                5 -> {
                    assertEquals(5, row.requireInt("ID"))
                    assertEquals("c", row.requireString("Field1"))
                }
                6 -> {
                    assertEquals(6, row.requireInt("ID"))
                    assertEquals("d", row.requireString("Field1"))
                }
                7 -> {
                    assertEquals(7, row.requireInt("ID"))
                    assertEquals("f", row.requireString("Field1"))
                }
                else -> {
                }
            }
        }
        assertEquals(7, rowCount)
    }
}

@ExperimentalCoroutinesApi
class JetTests {
    @Test
    fun jetTest() {
        runTest {
            JetTestBaseHelp("db-enc.mdb").let { help ->
                help.setup()
                help.openException?.printStackTrace()
                assertTrue(help.db.isOpen)
                val tableList = help.db.tableNameList()
                assertEquals(1, tableList.size)
                assertContentEquals(listOf("Table1"), tableList)
                help.verifyTable1()
            }
        }
    }

    @Test
    fun jet1997Test() {
        runTest {
            JetTestBaseHelp("db97-enc.mdb").let { help ->
                help.setup()
                help.openException?.let {
                    println(it.message)
                }
                assertTrue(help.db.isOpen)
                val tableList = help.db.tableNameList()
                assertEquals(1, tableList.size)
                assertContentEquals(listOf("Table1"), tableList)
                help.verifyTable1()
            }
        }
    }

    @Test
    fun office2007NoPasswordTest() {
        runTest {
            JetTestBaseHelp("db2007-oldenc.accdb").let {
                it.setup()
                assertFalse(it.db.isOpen)
                assertTrue(
                    it.openException?.message?.startsWith(DatabaseTestBaseHelp.wrongPassword)
                        ?: false
                )
            }
        }
    }

    @Test
    fun office2007BadPasswordTest() {
        runTest {
            JetTestBaseHelp("db2007-oldenc.accdb", "WrongPassword").let {
                it.setup()
                assertFalse(it.db.isOpen)
                assertTrue(
                    it.openException?.message?.startsWith(DatabaseTestBaseHelp.wrongPassword)
                        ?: false
                )
            }
        }
    }

    @Test
    fun office2007OldEncTest() {
        runTest {
            JetTestBaseHelp("db2007-oldenc.accdb", "Test123").let { help ->
                help.setup()
                help.openException?.let {
                    println(it.message)
                    it.printStackTrace()
                }
                assertTrue(help.db.isOpen)
                assertTrue(help.db.accessFile.codec is OfficeRC4Codec)
                val codec = help.db.accessFile.codec as OfficeRC4Codec
                assertEquals(4, codec.officeEncryption.majorVersion)
                assertEquals(2, codec.officeEncryption.minorVersion)
                val tableList = help.db.tableNameList()
                assertEquals(2, tableList.size)
                assertContentEquals(
                    listOf("Table1", "f_52BEE7C2589D48C4AF87C90C390CA532_Data"),
                    tableList
                )
                help.verifyOfficeTable1()
            }
        }
    }

    @Test
    fun office2007Test() {
        runTest {
            JetTestBaseHelp("db2007-enc.accdb", "Test123").let { help ->
                help.setup()
                help.openException?.let {
                    println(it.message)
                    it.printStackTrace()
                }
                assertTrue(help.db.isOpen)
                assertTrue(help.db.accessFile.codec is AgileCodec)
                val codec = help.db.accessFile.codec as AgileCodec
                assertEquals(4, codec.officeEncryption.majorVersion)
                assertEquals(4, codec.officeEncryption.minorVersion)
                val tableList = help.db.tableNameList()
                assertEquals(2, tableList.size)
                assertTrue(tableList.contains("Table1"))
                help.verifyOfficeTable1()
            }
        }
    }

    @Test
    fun office2013Test() {
        runTest {
            JetTestBaseHelp("db2013-enc.accdb", "1234").let { help ->
                help.setup()
                help.openException?.let {
                    println(it.message)
                    it.printStackTrace()
                }
                assertTrue(help.db.isOpen)
                val codec = help.db.accessFile.codec as AgileCodec
                assertEquals(4,codec.officeEncryption.majorVersion)
                assertEquals(4, codec.officeEncryption.minorVersion)
                val tableList = help.db.tableNameList()
                assertEquals(2, tableList.size)
                assertTrue(tableList.contains("Customers"))
                help.verifyCustomerTable()
            }
        }
    }
}