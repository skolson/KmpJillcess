package com.oldguy.jillcess.implementations

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class FileException(error: String, e: Throwable? = null) : Throwable(error, e)

class FileStructureException(
    error: String,
    e: Throwable? = null
) : Throwable(error, e)

interface DatabaseFile {
    enum class Mode(val value: String) { Read("r"), ReadWrite("rw") }

    fun enableCoroutines(coroutineContext: CoroutineContext = Dispatchers.Default)

    suspend fun open()

    suspend fun create(fileExtensions: FileExtensions)

    suspend fun close()

    suspend fun parsePage(pageNumber: Int)

    suspend fun parseTablePage(name: String, pageNumber: Int)

    suspend fun writePage(pageNumber: Int, buffer: ByteArray)
}
