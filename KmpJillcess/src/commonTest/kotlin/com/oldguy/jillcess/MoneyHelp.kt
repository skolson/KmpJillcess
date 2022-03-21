package com.oldguy.jillcess

import com.oldguy.common.io.File
import com.oldguy.common.io.FileMode
import com.oldguy.common.io.TextFile
import com.oldguy.jillcess.configuration.JsonConfiguration
import com.oldguy.jillcess.cryptography.Codec
import com.oldguy.jillcess.implementations.*
import com.oldguy.jillcess.utilities.DbMetadata
import com.soywiz.klock.DateTime
import com.soywiz.klock.Month
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

open class DatabaseTestBaseHelp(
    private val databaseName: String,
    private val password: String = "",
    private val subDir: String = ""
) {
    lateinit var db: AccessDatabase
        private set
    var openException: Throwable? = null

    private fun resourceDirectory(): File {
        return File(resourcePath).apply {
            if (!exists) throw IllegalStateException("Can't find resources: $resourcePath")
        }
    }

    private fun testDirectory(): File {
        return File(path).apply {
            if (!exists) throw IllegalStateException("Can't find TestFiles: $path")
        }
    }

    private suspend fun workDirectory() = testDirectory().resolve(work)

    suspend fun setup() {
        db = AccessDatabase(
            "$path$subDir$pathSeparator$databaseName",
            JsonConfiguration.build(resourceDirectory()),
            DatabaseFile.Mode.Read,
            password
        )
        try {
            db.open()
        } catch (exception: Throwable) {
            openException = exception
        }
    }

    companion object {
        const val pathSeparator = "/"
        const val resourcePath = "resources"
        const val path = "TestFiles"
        const val work = "Work"
        const val wrongPassword = Codec.passwordErrorText
    }

    fun generateColumnNames(table: AccessTable): String {
        var stmt = "val acctNames = listOf("
        table.columns.forEach {
            stmt += "${it.name},"
        }
        return "${stmt.trimEnd(',')})"
    }

    suspend fun dumpMetadata() {
        val metaData = DbMetadata()
        metaData.toHtml(db, TextFile(File(workDirectory(),"${db.name}.html"), mode = FileMode.Write))
    }

    suspend fun dumpCatalog() {
        val table = db.systemCatalogTable(AccessDatabase.systemCatalogName)
        val columns = emptyList<String>().toMutableList()
        table.columns.forEach { columns.add(it.name) }
        table.retrieve { rowCount, row ->
            var s = "$rowCount = "
            row.rowValues.forEach { s = "$s$it," }
            println(s)
            true
        }
    }

    suspend fun dumpIndex() {
        val ingredientsTable = db.table("Ingredients")
        val index = ingredientsTable.indexes["Ingredient"] as AccessIndex
        var rowCount = 0
        index.retrieve {
            rowCount++
            dumpRow(it, rowCount)
            true
        }
    }

    fun dumpColumnMetadata(table: AccessTable) {
        table.columns.forEach { column ->
            println("Column: ${column.name}, type:${column.type}, nullable: ${column.isNullable}, index:${column.index}, primaryKey: ${column.isPrimaryKey}")
            (column as AccessColumn).let {
                println("  metadata - decimal:${it.isDecimal}, variableLength:${it.isVariableLength}, accessTye:${it.def.columnType}, accessLength:${it.def.length}")
            }
        }
    }

    private fun dumpRow(row: AccessRow, rowCount: Int) {
        var s = "$rowCount = "
        row.rowValues.forEach {
            s = "$s${it.value},"
        }
        println(s)
    }
}

class MoneyTestBaseHelp(fileName: String, password: String = "", subDir: String = "")
    :DatabaseTestBaseHelp(
        fileName,
        password,
        subDir
    )
{
    suspend fun verifyCurrencyTable(version2008: Boolean = false) {
        val t = db.table("CRNC")
        val cols = listOf(
            "hcrnc",
            "szName",
            "lcid",
            "rgbFormat",
            "szIsoCode",
            "szSymbol",
            "fOnline",
            "dtSerial"
        )
        val colsV2 = cols + listOf("fHidden", "sguid", "fUpdated")
        if (version2008)
            assertContentEquals(colsV2, t.columnNames())
        else
            assertContentEquals(cols, t.columnNames())

        t.retrieveAll { rowCount, it ->
            if (version2008) {
                if (rowCount < 76)
                    assertEquals(rowCount, it.getInt("hcrnc"))
            } else
                assertEquals(rowCount, it.getInt("hcrnc"))
            when (rowCount) {
                1 -> {
                    assertTrue(validArgentineCurrencyNames.contains(it.getString("szName")))
                    assertEquals(11274, it.getInt("lcid"))
                    assertEquals("ARS", it.getString("szIsoCode"))
                    assertEquals("/ARSUS", it.getString("szSymbol"))
                }
                2 -> {
                    assertEquals("Australian dollar", it.getString("szName"))
                    assertEquals(3081, it.getInt("lcid"))
                    assertEquals("AUD", it.getString("szIsoCode"))
                    assertEquals("/AUDUS", it.getString("szSymbol"))
                }
                3 -> {
                    assertEquals("Austrian schilling", it.getString("szName"))
                    assertEquals(3079, it.getInt("lcid"))
                    assertEquals("ATS", it.getString("szIsoCode"))
                    assertEquals("/ATSUS", it.getString("szSymbol"))
                }
                4 -> {
                    assertEquals("Belgian franc", it.getString("szName"))
                    assertEquals(2060, it.getInt("lcid"))
                    assertEquals("BEF", it.getString("szIsoCode"))
                    if (version2008)
                        assertEquals("/BEFUS", it.getString("szSymbol"))
                    else
                        assertEquals("/BECUS", it.getString("szSymbol"))
                }
                else -> {
                }
            }
        }
    }

    companion object {
        val largeDate = DateTime(10000, Month.February, 28)
        val validArgentineCurrencyNames =
            listOf("Argentinean peso", "Argentinian peso", "Argentine peso")
        val tableNames2001 = listOf(
            "ACCT",
            "ADDR",
            "ADV",
            "ADV_SUM",
            "AUTO",
            "AWD",
            "Advisor Important Dates Custom Pool",
            "Asset Allocation Custom Pool",
            "BGT",
            "BGT_BKT",
            "BGT_ITM",
            "CAT",
            "CESRC",
            "CLI",
            "CLI_DAT",
            "CNTRY",
            "CRIT",
            "CRNC",
            "CRNC_EXCHG",
            "CT",
            "DHD",
            "FI",
            "Goal Custom Pool",
            "ITM",
            "IVTY",
            "Inventory Custom Pool",
            "LOT",
            "LSTEP",
            "MAIL",
            "MCSRC",
            "PAY",
            "PGM",
            "PMT",
            "PORT_REC",
            "POS_STMT",
            "PRODUCT",
            "PROJ",
            "PROV_FI",
            "PROV_FI_PAY",
            "Portfolio View Custom Pool",
            "Report Custom Pool",
            "SAV_GOAL",
            "SEC",
            "SEC_SPLIT",
            "SIC",
            "SOQ",
            "SP",
            "STMT",
            "SVC",
            "TAXLINE",
            "TMI",
            "TRIP",
            "TRN",
            "TRN_INV",
            "TRN_INVOICE",
            "TRN_OL",
            "TRN_SPLIT",
            "TRN_XFER",
            "TXSRC",
            "Tax Rate Custom Pool",
            "VIEW",
            "Worksheet Custom Pool",
            "XACCT",
            "XMAPACCT",
            "XMAPSAT",
            "XPAY"
        )

        fun tableNames2002(): List<String> = (tableNames2001 +
                listOf(
                    "BILL",
                    "BILL_FLD",
                    "UIE",
                    "UKSavings",
                    "UKWiz",
                    "UKWizAddress",
                    "UKWizCompanyCar",
                    "UKWizLoan",
                    "UKWizMortgage",
                    "UKWizPenScheme",
                    "UKWizPension",
                    "UKWizWillExecutor",
                    "UKWizWillGift",
                    "UKWizWillGuardian",
                    "UKWizWillLovedOne",
                    "UKWizWillMaker",
                    "UKWizWillPerson",
                    "UKWizWillResidue",
                    "UNOTE",
                    "XBAG"
                )
            ).sorted()

        fun tableNames2008(): List<String> = (
                tableNames2001.filterNot { it == "Goal Custom Pool" }  +
                listOf(
                    "BILL",
                    "BILL_FLD",
                    "Feature Expiration Custom Pool",
                    "PM_RPT",
                    "PREF",
                    "PREF_LIST",
                    "SCHE_TASK",
                    "Tax Scenario Custom Pool",
                    "UIE",
                    "UI_VIEW",
                    "UNOTE",
                    "X_FMLA",
                    "X_ITM",
                    "X_META_REF",
                    "X_PARM",
                    "XBAG",
                    "XMAPSEC",
                    "XSYNCCHUNK"
                )
            ).sorted()

    }
}