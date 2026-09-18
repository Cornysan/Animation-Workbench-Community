package com.playmation.motionlabsbackend.format

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Dieselben Testdateien und Erwartungen wie die C#-Tests im Community-Modul
 * der Animation Workbench. Gleiche Codes und Hashes heißt: Client und Server
 * lehnen dasselbe ab und erkennen dieselben Duplikate.
 */
class AwclipReaderTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/awclip/$name")) { "missing fixture $name" }.readText(Charsets.UTF_8)

    @TestFactory
    fun `fixtures match the expectations shared with the Unity client`(): List<DynamicTest> {
        val fixtures = (StrictJson.parse(resource("expectations.json")) as StrictJson.Value.Obj)["fixtures"]
            as StrictJson.Value.Arr

        return fixtures.items.map { item ->
            val entry = item as StrictJson.Value.Obj
            val file = (entry["file"] as StrictJson.Value.Str).value
            val error = (entry["error"] as? StrictJson.Value.Str)?.value
            val hash = (entry["hash"] as? StrictJson.Value.Str)?.value

            DynamicTest.dynamicTest(file) {
                val result = AwclipReader.readJson(resource(file))
                if (error == null) {
                    val ok = assertIs<AwclipReadResult.Ok>(result, "$file should be valid: $result")
                    assertEquals(hash, AwclipHash.compute(ok.document), "$file content hash")
                } else {
                    val rejected = assertIs<AwclipReadResult.Rejected>(result, "$file should be rejected")
                    assertEquals(error, rejected.error.code, "$file error code")
                }
            }
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun `gzip container round trip keeps the hash`() {
        val json = resource("valid-full.json")
        val fromJson = assertIs<AwclipReadResult.Ok>(AwclipReader.readJson(json))
        val fromFile = assertIs<AwclipReadResult.Ok>(AwclipReader.readFile(gzip(json.toByteArray()).inputStream()))
        assertEquals(AwclipHash.compute(fromJson.document), AwclipHash.compute(fromFile.document))
    }

    @Test
    fun `plain json is not a container`() {
        val result = AwclipReader.readFile("{\"format\":\"awclip\"}".byteInputStream())
        assertEquals("not-gzip", assertIs<AwclipReadResult.Rejected>(result).error.code)
    }

    @Test
    fun `decompression bomb is stopped`() {
        val bomb = gzip(ByteArray(70 * 1024 * 1024))
        val result = AwclipReader.readFile(bomb.inputStream())
        assertEquals("too-large", assertIs<AwclipReadResult.Rejected>(result).error.code)
    }

    @Test
    fun `oversized compressed file is stopped before decompressing`() {
        val noise = ByteArray((AwclipSchema.MAX_COMPRESSED_BYTES + (1 shl 20)).toInt()).also { Random(7).nextBytes(it) }
        val result = AwclipReader.readFile(gzip(noise).inputStream())
        assertEquals("too-large", assertIs<AwclipReadResult.Rejected>(result).error.code)
    }

    @Test
    fun `invalid utf8 is rejected`() {
        val result = AwclipReader.readFile(gzip(byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte(), 0x7d)).inputStream())
        assertEquals("not-utf8", assertIs<AwclipReadResult.Rejected>(result).error.code)
    }

    @Test
    fun `strict json edge cases`() {
        fun code(json: String) = try {
            StrictJson.parse(json); null
        } catch (ex: StrictJson.JsonException) {
            ex.code
        }

        assertEquals("json-syntax", code("[1,]"))
        assertEquals("json-syntax", code("[01]"))
        assertEquals("json-syntax", code("// x\n{}"))
        assertEquals("json-syntax", code("[Infinity]"))
        assertEquals("json-number-range", code("[1e400]"))
        assertEquals("json-syntax", code("﻿{}"))
        assertEquals("json-string-length", code("\"" + "x".repeat(StrictJson.MAX_STRING_LENGTH + 1) + "\""))
        assertEquals(null, code("{\"a\":[true,false,null,-0.5e-3,\"\\u00e9\\n\"]}"))
    }
}
