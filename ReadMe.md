## KmpJillcess

I have trouble imagining why anyone else but me would want to read old MS Access (and MS Money) data files on an Apple or Android device (for examples), but I posted this repo anyway as part of refactoring it out of the project it was in (as a sub-module), into it's own library :-)  This library was written early in Kotlin's life (1.2.x) and was one of the first Kotlin projects I did (after kmp-io). I'm sure that Kotlin newbie skill level shows in the code, sorry in advance.

So if you actually have some use case for reading Access files with Kotlin, enjoy - otherwise bail now :-)

The repo name is a derivative of the Jackcess Java project, which was an invaluable resource.

This Repo is a Kotlin multiplatform (KMP) library that provides a simple database api for reading MS Access (and Microsoft Money) database files. The specification for these files is not public, heavy reliance was made on these information sources:

- Jackcess project on Sourceforge and also mirrored on Github - [Jackcess](https://jackcess.sourceforge.io)
- MDBTools github - [MDB Tools](https://github.com/mdbtools/mdbtools)
- [The unofficial MDB Guide](http://jabakobob.net/mdb)

This library was fun to make and taught me more than I ever wanted to know about MS Access files.  The original requirement causing it's birth was to convert an old but large Microsoft Money file to Sqlite/Sqlcipher.  An API similar to the one used in the SqlEncrypt repo for Sqlite/SqlCipher was used to make the access tables, column metadata, index metadata, and other MS Access artifacts accessible to a KMP program with type saftey on all supported column types. Access files were designed I think sometime in the 80s, when storage and memory were both expensive. So it is crawling with bit masks, bitmap index structures, condensed encodings, and all the other artifacts you can imagine a C programmer might come up with back in the day to save money on memory and hard storage. The files formats changed some between the Jet3 and the Jet4 versions, both of which this library supports, but the basic architecture stayed the same.  The file is organized into pages of fixed size. Page Zero is full of metadata about the rest of the file. All the other pages have a specific page type of which there are a small number.  Each page type has its own organization.  Its WAY out of the scope of this readme to go into all the details.

I think 100 percent of the code required in this library is Kotlin, with no requirement for expect/actual classes with platform-specific code. It does use two libraries that do have platform-specific code. See the dependencies below.

Access does some stuff this library doesn't support. But it does handle the basic column types:

- Character/Text mapped to String
- Boolean
- short types mapped to Short
- integer types mapped to Int
- long types mapped to Long
- decimal types mapped to BigDecimal from the KMP library Bignum from Ionspin
- date and datetime types mapped to the Klock library
- Blob and Clob columns maped to ByteArray

Various versions of Access have encryption abilities with various engines, salts, key schemes that change by page number, and other techniques that have morphed over the years, of which this library supports most.

#### Versions supported

- Jet3 (using 2K pages)
- Jet4 (using 4K pages)
- MS Money 2001, 2002, 2008 with and without password protection
- Access 97 with and without encryption
- Office 2007, 2013 with and without encryption
- Others may work. The format hasn't changed much, but encryption schemes have gone through multiple changes over the years. 

#### Supported targets:

- Android X64 and Arm64 ABIs
- macosX64
- iosX64
- iosArm64
- jvm
- mingw64 currently not supported but is **easy** to add.
- linuxX64 currently not supported but is **easy** to add.

#### Dependencies

- KmpIO (kmp-io 0.1.2) repo is used for all file IO - The random access RawFile, ByteBuffer, BitSet and other classes are used extensively.
- KmpCrypto (kmp-crypto) is used for the cryptography schemes the various releases of Access have used over the years.

#### SourceSets

Almost everything is in commonMain in package com.oldguy.jillcess. There are a bunch of other subdirectories for platform-specific code as they come from a KMP project template. There is only one use case for expect/actual classes, as the Office versions of Access use a small chunk of XML to encode encryption metadata. 

A common source set "appleNativeMain" contains common code used by all three Apple targets. It only contains code for use of an Apple-platform XML parser that is shared across the IOS and Mac targets.

## Reason for Existence

The original reasons for this library were these:

- A requirement to read an encrypted MS Money file with many years of personal financial data, and Money's export tools were not good (unacceptable). I had been using Jackcess in a prior Android-only java poject, but as part of converting that to KMP wanted a KMP flavor of the Access code using no Java.
- Also had a need to read databases from various releases of Access (with and without encryption) 
- I need a good Kotlin learning experience :-)

There were other reasons but these were the ones that counted. Basic features desired:

- Coroutine support
- Kotlin-friendly DSL syntax for reading Access Tables, system dictionary, Indexes.
- Cryptography support for the various releases of Access
- Type-safe Table/Column usage similar to what I had for Sqlite (KmpSqlencrypt repo). No SQL support of course, just table reads.

## Notes

This library has been used extensively in one app, so has not so far been published to maven. It can be easily published to mavenLocal using the gradle "publishToMavenLocal" task.

At some point the library may be published to the public Maven repository if a miracle occurs resulting in any interest.

Until that happens, use the gradle Publish task 'publishToMavenLocal' to run a build and publish the artifacts produced to a local maven repository. Note if the publishToMavenLocal task is run on a Mac, it can build most of the supported targets (not mingw64 or linuxX64). Publishing on Linux or Windows will not build the apple targets. 

## Dependency

Define the library as a gradle dependency (assumes mavenLocal is defined as a repo in your build.gradle scripts):

```
    dependencies {
        implementation("com.oldguy:kmp-jillcess:0.1.0")
        implementation("com.oldguy:kmp-io:0.1.2")
        implementation("com.oldguy:kmp-crypto:0.1.2")
    }  
```

## Coroutine support

Since everything in Access involves Random Access file IO, use something like Dispatchers.IO to run these functions. 

# Example Usage

This example opens an encrypted MS Money database using a password, then read rows from the currency table ("CRNC"). The exmple if for the 2008 version, which added a few ne columns to this table.

```
    suspend fun readCurrencyExample() {
        AccessDatabase(
            "$path$subDir$pathSeparator$databaseName",
            JsonConfiguration.build(testDirectory()),
            DatabaseFile.Mode.Read,
            password
        ).apply {
            try {
                open()               // AccessDatabase instance has lots of metadata about version, DB versions, encryption used etc. Also builds a SystemCatalog instance with indexes, tables
                systemCatalogList()  // returns a list of all tables in the Access SystemCatalog
                                     // tables contain metadata like column names and column type description properties
                val currencyTable = table("CRNC")   // Table instance has table metadata and columns metadata for that table.
                
                table("CRNC").retrieveAll { rowCount, it ->
                    val key = it.requireInt("hcrnc")                // msmoney often starts key column names with "h" (for handle?)
                    val currencyName = it.requireString("szName")   // use get for nullable columns, require for not nullable    
                    val dt = it.getDateTime("dtSerial")             // returns a LocalDateTime instance from the Klock library
                }
                
                close()
            } catch (exception: Throwable) {
                // errors thrown for bad passowrds, invalid files, etc
            }
        }
```

### Column Types

The basic column types are mapped to Kotlin-equivalents. There is a sealed class AccessRowValue<T> with a subclass for each basic type that takes care of mapping. For example class ByteValue: AccessRowValue<Byte> is used for mapping single-byte column values.

| Type name | AccessRowValue Type | Kotlin Type |
|-----------|---------------------|-------------|
| NUMBER    | ByteValue             | Byte      |
| CHAR      | StringValue           | String |
| VARCHAR   | StringValue           | String |
| TEXT      | StringValue           | String |
| BOOLEAN   | BooleanValue          | Boolean   |
| NUMBER    | ShortValue            | Short |
| NUMBER    | IntValue              | Int   |
| NUMBER    | LongValue             | Long  |
| DECIMAL   | DecimalValue          | BigDecimal (ionspin bignum library)   |
| FLOAT     | FloatValue            | Float |
| DOUBLE    | DoubleValue           | Double |
| MEMO      | MemoValue             | ByteArray (can be up to 1GB if memory permits) |
| GUID      | GuidValue             | fixed length String |
| BLOB      | BinaryValue           | ByteArray |
| Complex   | ComplexValue          / ByteArray (parsing not currently supported) |

Notes

- DECIMAL types have precision and scale metadata that are set into the BigDecimal instance. It has full control over rounding and no limits on precision or scale. The Ionspin Bignum KMP library is used for this until such toime as Kotlin has formal BigDecimal and BigInteger support.
- The NUMBER type in access can be 1,2,4,8, or 16 bytes long
- LocalDate and LocalDateTime instances are from the Klock library. As of this writing, kotlinx.datetime doesn't yet have full parsing/formatting support. Once it does, that library will be added as a mapping option.
- All types except Boolean are nullable, so the `get` functions get null for null column values, `require` functions don't support null and will throw an exception if used on a null column value.


## Maintainability notes

It is fairly easy to add support for new column types, such as the Complex type.  If there is a need for that, start a repo Discussion or an Issue.

The library is read-only access. Enabling write access is doable but non-trivial. The object design for the various Page types, Usage Maps, Record types, Index encodings, etc in the Access specification are fairy flexible but also complex, so write access (like Jackcess supports) is work :-)

New release support (like 2019 or later) is also likely easy - complexity in new releases is often related to changes the encryption used, not changes to the legacy Jet stuff that all releases use. If new hash algorithms or encryption engines are introduced, the strategy will be to use (or enhance) the kmp-crypto library so no additional platform-specific code is required.