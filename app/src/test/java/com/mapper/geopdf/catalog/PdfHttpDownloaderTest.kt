package com.mapper.geopdf.catalog

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class PdfHttpDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resumesPartialDownloadAcrossRedirectWithUnencodedSpaces() {
        val fullPayload = ("%PDF-1.7\n" + "0123456789ABCDEF".repeat(64) + "\n%%EOF\n").toByteArray(Charsets.US_ASCII)
        val splitPoint = fullPayload.size / 2
        val requests = CopyOnWriteArrayList<Map<String, String>>()

        val server = startMockServer { path, headers, output ->
            requests.add(mapOf("path" to path) + headers)
            when (path) {
                "/downloadMap.php" -> {
                    val body = ""
                    output.write(
                        (
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: /files/Test Forest Map.pdf\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                }
                "/files/Test%20Forest%20Map.pdf" -> {
                    assertEquals("bytes=$splitPoint-", headers["range"])
                    assertEquals("\"etag-v1\"", headers["if-range"])
                    val slice = fullPayload.copyOfRange(splitPoint, fullPayload.size)
                    output.write(
                        (
                            "HTTP/1.1 206 Partial Content\r\n" +
                                "Content-Type: application/pdf\r\n" +
                                "ETag: \"etag-v1\"\r\n" +
                                "Content-Range: bytes $splitPoint-${fullPayload.lastIndex}/${fullPayload.size}\r\n" +
                                "Content-Length: ${slice.size}\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII)
                    )
                    output.write(slice)
                }
                else -> fail("Unexpected path: $path")
            }
        }

        try {
            val partFile = File(tempFolder.root, "Test Forest Map.pdf.part")
            val etagFile = File(tempFolder.root, "Test Forest Map.pdf.etag")
            partFile.writeBytes(fullPayload.copyOfRange(0, splitPoint))
            etagFile.writeText("\"etag-v1\"")

            val progressUpdates = mutableListOf<Pair<Long, Long?>>()
            PdfHttpDownloader.downloadToPartFile(
                initialUrl = "http://127.0.0.1:${server.localPort}/downloadMap.php",
                partFile = partFile,
                etagFile = etagFile,
                expectedSizeBytes = fullPayload.size.toLong(),
                catalogEtag = "\"etag-v1\"",
                isActiveCheck = { true },
                onConnectionChanged = {},
                onProgress = { dl, total -> progressUpdates.add(dl to total) }
            )

            assertArrayEquals(fullPayload, partFile.readBytes())
            assertTrue(PdfHttpDownloader.hasValidPdfEnvelope(partFile))
            assertTrue(progressUpdates.first().first == splitPoint.toLong())
            assertEquals(2, requests.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun restartsFromZeroWhenServerChangesEtagDuringPartialResume() {
        val v2Payload = ("%PDF-1.7\nNEW_CONTENT_VERSION_2\n" + "XYZ".repeat(80) + "\n%%EOF\n").toByteArray(Charsets.US_ASCII)
        val seenRanges = CopyOnWriteArrayList<String?>()

        val server = startMockServer { _, headers, output ->
            val range = headers["range"]
            seenRanges.add(range)
            if (range != null) {
                val slice = v2Payload.copyOfRange(10, v2Payload.size)
                output.write(
                    (
                        "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: application/pdf\r\n" +
                            "ETag: \"etag-v2\"\r\n" +
                            "Content-Length: ${slice.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(slice)
            } else {
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/pdf\r\n" +
                            "ETag: \"etag-v2\"\r\n" +
                            "Content-Length: ${v2Payload.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(v2Payload)
            }
        }

        try {
            val partFile = File(tempFolder.root, "Updated.pdf.part")
            val etagFile = File(tempFolder.root, "Updated.pdf.etag")
            partFile.writeBytes("OLD_CORRUPT_STALE_PREFIX".toByteArray(Charsets.US_ASCII))
            etagFile.writeText("\"etag-v1\"")

            PdfHttpDownloader.downloadToPartFile(
                initialUrl = "http://127.0.0.1:${server.localPort}/Updated.pdf",
                partFile = partFile,
                etagFile = etagFile,
                expectedSizeBytes = null,
                catalogEtag = "\"etag-v1\"",
                isActiveCheck = { true },
                onConnectionChanged = {},
                onProgress = { _, _ -> }
            )

            assertArrayEquals(v2Payload, partFile.readBytes())
            assertEquals("\"etag-v2\"", etagFile.readText())
            assertEquals(listOf("bytes=24-", null), seenRanges)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsTruncatedTransferAndMissingPdfEof() {
        val headerOnly = ("%PDF-1.7\n/VP [] /GPTS [0 0 1 1] /MediaBox [0 0 100 100]\n" + "A".repeat(300)).toByteArray(Charsets.US_ASCII)
        val declaredLength = headerOnly.size + 1000

        val server = startMockServer { _, _, output ->
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/pdf\r\n" +
                        "ETag: \"trunc-etag\"\r\n" +
                        "Content-Length: $declaredLength\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            output.write(headerOnly)
        }

        try {
            val partFile = File(tempFolder.root, "Truncated.pdf.part")
            val etagFile = File(tempFolder.root, "Truncated.pdf.etag")

            try {
                PdfHttpDownloader.downloadToPartFile(
                    initialUrl = "http://127.0.0.1:${server.localPort}/Truncated.pdf",
                    partFile = partFile,
                    etagFile = etagFile,
                    expectedSizeBytes = declaredLength.toLong(),
                    catalogEtag = "\"trunc-etag\"",
                    isActiveCheck = { true },
                    onConnectionChanged = {},
                    onProgress = { _, _ -> }
                )
                fail("Expected IOException for truncated transfer")
            } catch (_: IOException) {
                // Expected: .part must stay preserved at received byte length for resume
            }

            assertTrue(partFile.exists())
            assertEquals(headerOnly.size.toLong(), partFile.length())
            assertFalse(PdfHttpDownloader.hasValidPdfEnvelope(partFile))
        } finally {
            server.close()
        }
    }

    private fun startMockServer(
        handler: (path: String, headers: Map<String, String>, output: java.io.OutputStream) -> Unit
    ): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                        val requestLine = reader.readLine() ?: return@use
                        val path = requestLine.split(" ").getOrElse(1) { "/" }
                        val headers = LinkedHashMap<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val idx = line.indexOf(':')
                            if (idx > 0) {
                                headers[line.substring(0, idx).trim().lowercase()] =
                                    line.substring(idx + 1).trim()
                            }
                        }
                        val out = s.getOutputStream()
                        handler(path, headers, out)
                        out.flush()
                    }
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }
        return server
    }
}
