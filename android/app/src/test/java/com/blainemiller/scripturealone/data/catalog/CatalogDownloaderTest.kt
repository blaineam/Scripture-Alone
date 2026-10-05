package com.blainemiller.scripturealone.data.catalog

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import java.nio.file.Files

/**
 * The eBible.org download against a loopback HTTP server (nothing leaves the machine) —
 * `CatalogDownloaderTests.swift` on iOS: the file written, progress, HTTP errors, an empty body and a
 * connection dropped half way, none of which may leave a file behind.
 */
class CatalogDownloaderTest {
    /**
     * A one-file-per-path HTTP/1.1 server on a loopback socket. (The JDK's `com.sun.net.httpserver`
     * isn't on an Android module's unit-test classpath.)
     */
    private class LoopbackServer {
        class Route(val status: Int, val body: ByteArray, val chunked: Boolean, val truncated: Boolean)

        val routes = ConcurrentHashMap<String, Route>()
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { client.use { serve(it) } }
                }
            }
        }

        private fun serve(client: java.net.Socket) {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return
            while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break }
            val route = routes[path] ?: Route(404, ByteArray(0), false, false)
            val out = client.getOutputStream()
            val head = StringBuilder("HTTP/1.1 ${route.status} X\r\nConnection: close\r\n")
            when {
                route.truncated -> head.append("Content-Length: ${route.body.size * 2}\r\n")
                !route.chunked -> head.append("Content-Length: ${route.body.size}\r\n")
            }
            out.write(head.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
            out.write(route.body)
            out.flush()
        }

        fun close() = socket.close()
    }

    private lateinit var server: LoopbackServer
    private lateinit var directory: File

    @Before fun setUp() {
        server = LoopbackServer()
        directory = Files.createTempDirectory("catalog-download").toFile()
    }

    @After fun tearDown() {
        server.close()
        directory.deleteRecursively()
    }

    private fun url(path: String) = URL("http://127.0.0.1:${server.port}$path")

    /**
     * [body] at [path]. [chunked] sends no length (read to the end of the connection); [truncated]
     * promises twice the bytes it sends, then hangs up — a connection lost half way.
     */
    private fun serve(path: String, status: Int = 200, body: ByteArray = ByteArray(0), chunked: Boolean = false, truncated: Boolean = false) {
        server.routes[path] = LoopbackServer.Route(status, body, chunked, truncated)
    }

    @Test fun writesTheZipAndReportsProgressUpToOne() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        serve("/engwebp_usfm.zip", body = body)
        val progress = mutableListOf<Double?>()
        val file = CatalogDownloader.download(url("/engwebp_usfm.zip"), "engwebp", directory) { progress += it }
        assertArrayEquals(body, file.readBytes())
        assertTrue(file.name.startsWith("engwebp-") && file.name.endsWith(".zip"))
        assertEquals(directory, file.parentFile)
        assertEquals(1.0, progress.last())
        val known = progress.filterNotNull()
        assertEquals(known.sorted(), known)
        assertTrue(known.all { it in 0.0..1.0 })
    }

    @Test fun withoutALengthProgressIsIndeterminateUntilDone() {
        serve("/x.zip", body = ByteArray(10) { 1 }, chunked = true)
        val progress = mutableListOf<Double?>()
        CatalogDownloader.download(url("/x.zip"), "x", directory) { progress += it }
        assertEquals(1.0, progress.last())
        assertTrue(progress.dropLast(1).all { it == null })
    }

    @Test fun anHttpErrorThrowsAndLeavesNothing() {
        serve("/gone.zip", status = 404, body = "nope".toByteArray())
        val error = runCatching { CatalogDownloader.download(url("/gone.zip"), "gone", directory) }.exceptionOrNull()
        assertTrue("got $error", error is CatalogDownloader.Failure.Http && error.code == 404)
        assertTrue(error!!.message!!.contains("404"))
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun anEmptyBodyThrowsAndLeavesNothing() {
        serve("/empty.zip", body = ByteArray(0))
        val error = runCatching { CatalogDownloader.download(url("/empty.zip"), "empty", directory) }.exceptionOrNull()
        assertTrue("got $error", error is CatalogDownloader.Failure.Empty)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun aDroppedConnectionThrowsAndLeavesNoPartialFile() {
        serve("/cut.zip", body = ByteArray(200_000) { 7 }, truncated = true)
        val error = runCatching { CatalogDownloader.download(url("/cut.zip"), "cut", directory) }.exceptionOrNull()
        assertTrue("got $error", error is IOException)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun anUnreachableServerThrowsAndLeavesNothing() {
        val port = server.port
        server.close()
        val error = runCatching { CatalogDownloader.download(URL("http://127.0.0.1:$port/x.zip"), "x", directory) }.exceptionOrNull()
        assertTrue("got $error", error is IOException)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun theAbbreviationComesFromTheTitlesInitials() {
        assertEquals("WEB", CatalogIdentity.abbreviation("World English Bible", "engwebp"))
        assertEquals("ASV", CatalogIdentity.abbreviation("American Standard Version (1901)", "eng-asv"))
        assertEquals("BBE", CatalogIdentity.abbreviation("Bible in Basic English", "engbbe"))
        // Too long or too short for a toolbar: the id instead.
        assertEquals("ENGXYZ", CatalogIdentity.abbreviation("A", "eng-xyz"))
        assertEquals("IMPORT", CatalogIdentity.abbreviation("", "--"))
    }
}
