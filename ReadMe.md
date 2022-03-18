## KmpJillcess

I have trouble imagining why anyone else but me would want to read MS Access (and MS Money) data files on an Apple device for example, but I posted this repo anyway as part of refactoring it out of the project it was in (as a sub-module), into it's own library :-)  This library was written early in Kotlin's life (1.2.x) and was one of the first Kotlin projects I did. I'm sure that Kotlin newbie skill level will show in the code.

So if you actually have some use case for reading Access files with Kotlin, enjoy - otherwise bail now :-)

The repo name is a derivative of the Jackcess Java project.

This Repo is a Kotlin multiplatform (KMP) library that provides a simple database api for reading MS Access database files. The specification for these files is not public, heavy reliance was made on these information sources:

- Jackcess project - 
- [Bouncy Castle](https://www.bouncycastle.org/)

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

Access also has encryption abilities that have morphed over the years, of which this library supports most.

#### Versions supported

- Jet3 (using 2K pages)
- Jet4 (using 4K pages)

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

Almost everything is in commonMain in package com.oldguy.jillcess. There are a bunch of other subdirectories for platform-specific code that are empty as they come from a KMP project template. 

A common source set "appleNativeMain" contains common code used by all three Apple targets. It's empty two because no native Apple code is required for this library. Again it cam e from my template and I left it.

## Reason for Existence

The original reasons for this library were these:

- I had an encrypted MS Money file with many years of personal fionancial data I wanted to extract, and Money's export tools were not good (unacceptable)
- I need a good Kotlin learning experience :-)

There were other reasons but these were the ones that counted. Basic features desired:

- Coroutine support
- Kotlin-friendly DSL syntax for reading Access Tables, system dictionary, Indexes.
- Cryptography support for the various releases of Access
- Type-safe access similar to what I had for Swlite (KmpSqlencrypt repo)
- MS Money used an older flavor of the Jet4 file spec, an MS Money support was a MUST
- Newer Access versions (at least to 2008) for the ability to convert data out of newer Access databases

## Notes

This library has been used extensively in one app, so has not so far been published to maven. It can be easily published to mavenLocal using the gradle "publishToMavenLocal" task.

At some point the library may be published to the public Maven repository if a miracle occurs resulting in any interest.

Until that happens, use the gradle Publish task 'publishToMavenLocal' to run a build and publish the artifacts produced to a local maven repository. Note if the publishToMavenLocal task is run on a Mac, it can build most of the supported targets (not mingw64 or linuxX64). Publishing on Linux or Windows will not build the apple targets. 

## Dependency

Define the library as a gradle dependency (assumes mavenLocal is defined as a repo in your build.gradle scripts):

```
    dependencies {
        implementation("com.oldguy:kmp-jillcess:0.1.0")
        implementation("com.oldguy:kmp-io:0.1.1")
    }  
```

## Coroutine support

Since everything in Access involves Random Access file IO, use something like Dispatchers.IO to run these functions. 

# Example Usage

