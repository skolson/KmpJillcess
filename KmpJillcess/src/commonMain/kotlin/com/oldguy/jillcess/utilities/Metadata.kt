package com.oldguy.jillcess.utilities

import com.oldguy.common.io.TextFile
import com.oldguy.jillcess.implementations.AccessColumn
import com.oldguy.jillcess.implementations.AccessDatabase
import com.oldguy.jillcess.implementations.AccessSystemCatalog
import com.oldguy.jillcess.implementations.AccessTable

class DbMetadata {

    suspend fun toHtml(db: AccessDatabase, htmlFile: TextFile) {
        val out = StringBuilder()
        html {
            head {
                title { +"Metadata for Access Database ${db.filePath}" }
                style { +"table, th, td { border: 1px solid black; border-collapse: collapse; }" }
                style { +"th, td { padding: 15px; }" }
            }
            body {
                h1 { +"Metadata for Access Database ${db.filePath}" }
                b { +"Linked Databases" }
                ul {
                    db.linkedDatabases.forEach {
                        li {
                            +it.key
                        }
                    }
                }
                b { +"System Tables" }
                ul {
                    for ((key, _) in db.accessSystemCatalog.systemTables) {
                        li {
                            +key
                        }
                    }
                }
                b { +"User Tables" }
                ul {
                    for ((key, value) in db.userTableRows) {
                        li {
                            +key
                        }
                        ul {
                            li { +"type: ${value.type}" }
                            li { +"id: ${value.id}" }
                            li { +"parentId: ${value.parentId}" }
                            if (value.isLinkedDb) {
                                li { +"linkedDb: ${value.linkedDb}" }
                                li { +"foreignName: ${value.foreignName}" }
                            }
                        }
                    }
                }
                b { +"Local User Table Details" }
                ul {
                    for ((name, row) in db.userTableRows) {
                        if (row.type != AccessSystemCatalog.Type.LocalTable) continue
                        li { +"Table: $name, Columns List" }
                        table {
                            tr {
                                th { +"Name" }
                                th { +"Type" }
                                th { +"Length Type" }
                                th { +"Length" }
                                th { +"Nullable" }
                                th { +"Id" }
                            }
                            val table = db.table(name) as AccessTable
                            table.columns.map { it as AccessColumn }.forEach {
                                tr {
                                    td { +it.name }
                                    td { +"${it.type}" }
                                    val lt = if (it.isFixedLength) "Fixed" else "Variable"
                                    td { +lt }
                                    td { +"${it.def.length}" }
                                    td { +"${it.isNullable}" }
                                    td { +"${it.def.columnId}" }
                                }
                            }
                        }
                    }
                }
            }
        }.render(out, "  ")
        htmlFile.write(out.toString())
        htmlFile.close()
    }
}
