package com.openai.companion.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Real packaged worker, Rust store, Keychain and production gateway; model output is deterministic. */
class DesktopWorkerAcceptanceTest {
    @Test fun packagedWorkerClaimsAndReturnsWithoutAWindowOrReplayAfterRestart() {
        val executable = System.getenv("COMPANION_TEST_WORKER_APP")
        assumeTrue(!executable.isNullOrBlank())
        val factory = File(System.getenv("COMPANION_TEST_PREFS_JAR") ?: error("Missing isolated preferences jar"))
        require(factory.isFile)
        val python = System.getenv("COMPANION_TEST_SERVICE_PYTHON") ?: "python3"
        val directory = Files.createTempDirectory("companion-worker-acceptance").toFile()
        val calls = AtomicInteger()
        val model = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        model.createContext("/v1/chat/completions") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            check(request.contains("acceptance-task"))
            calls.incrementAndGet()
            val reply = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("message", buildJsonObject {
                        put("content", """{"state":"completed","text":"acceptance-result"}""")
                    })
                }) })
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        model.start()
        val port = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$port"
        val serviceScript = """
            import json, sys, uvicorn, logging
            from companion_service import CompanionService
            logging.basicConfig(level=logging.INFO,stream=sys.stderr,format='%(message)s')
            app=CompanionService(sys.argv[1],sys.argv[2])
            print(json.dumps({'mac':app.credentials.issue('acceptance','mac',['tasks']),
                              'phone':app.credentials.issue('acceptance','phone',['tasks'])}),flush=True)
            uvicorn.run(app,host='127.0.0.1',port=int(sys.argv[3]),access_log=False)
        """.trimIndent()
        val service = ProcessBuilder(python, "-c", serviceScript,
            directory.resolve("server").path, base, "$port")
            .directory(File("../scripts")).redirectError(directory.resolve("service.log")).start()
        val account = "a2a:device-tasks:$base"
        val secretFile = directory.resolve("credential.properties")
        val data = directory.resolve("worker").apply { mkdirs() }
        var worker: Process? = null
        fun startWorker(cleanup: Boolean = false): Process = ProcessBuilder(executable!!, "--worker").apply {
            environment()["COMPANION_DATA_DIR"] = data.path
            environment()["JAVA_TOOL_OPTIONS"] = "-Xbootclasspath/a:${factory.absolutePath} " +
                "-Djava.util.prefs.PreferencesFactory=AcceptancePreferencesFactory " +
                "-Dcompanion.acceptance.modelEndpoint=http://127.0.0.1:${model.address.port}/v1/chat/completions " +
                "-Dcompanion.acceptance.secretFile=${secretFile.absolutePath} " +
                "-Dcompanion.acceptance.cleanup=$cleanup"
            redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("worker.log")))
        }.start()
        fun waitFor(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < deadline) {
                if (condition()) return
                if (worker?.isAlive == false) error("Worker exited: ${directory.resolve("worker.log").readText()}")
                Thread.sleep(100)
            }
            val stack = directory.resolve("worker-stack.log")
            worker?.let {
                runCatching { ProcessBuilder("jcmd", "${it.pid()}", "Thread.print")
                    .redirectErrorStream(true).redirectOutput(stack).start().waitFor(3, TimeUnit.SECONDS) }
            }
            error("Timed out: $description\n" + directory.resolve("worker.log").readText() +
                directory.resolve("service.log").readText() + if (stack.isFile) stack.readText() else "")
        }
        fun request(path: String, token: String? = null, payload: String? = null): JsonObject {
            val connection = URI(base + path).toURL().openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 1000; connection.readTimeout = 3000
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (payload != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("A2A-Version", "1.0")
                connection.outputStream.use { it.write(payload.toByteArray()) }
            }
            return try { connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject } }
            finally { connection.disconnect() }
        }
        try {
            val identities = Json.parseToJsonElement(service.inputStream.bufferedReader().readLine()).jsonObject
            val mac = identities.getValue("mac").jsonObject.getValue("token").jsonPrimitive.content
            val phone = identities.getValue("phone").jsonObject.getValue("token").jsonPrimitive.content
            waitFor("gateway ready") { runCatching { request("/readyz")["ok"] == JsonPrimitive(true) }.getOrDefault(false) }
            secretFile.writeText("account=$account\ntoken=$mac\n")
            Files.setPosixFilePermissions(secretFile.toPath(), setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE))
            data.resolve("device-tasks.json").writeText(buildJsonObject {
                put("endpoint", base); put("name", "Acceptance worker"); put("acceptTasks", true)
            }.toString())
            worker = startWorker()
            waitFor("background registration") {
                val devices = request("/v1/devices", phone).getValue("devices").jsonArray
                devices.any { it.jsonObject["backgroundExecution"] == JsonPrimitive(true) &&
                    it.jsonObject["foreground"] == JsonPrimitive(false) }
            }
            val sent = request("/agents/mac/rpc", phone, """{"jsonrpc":"2.0","id":"send","method":"SendMessage","params":{"message":{"messageId":"acceptance-message","role":"ROLE_USER","parts":[{"text":"acceptance-task"}]},"configuration":{"returnImmediately":true}}}""")
            val taskId = sent.getValue("result").jsonObject.getValue("task").jsonObject.getValue("id").jsonPrimitive.content
            fun remoteTask() = request("/agents/mac/rpc", phone, """{"jsonrpc":"2.0","id":"get","method":"GetTask","params":{"id":"$taskId"}}""").getValue("result").jsonObject
            waitFor("task completion") { remoteTask()["status"]!!.jsonObject["state"] == JsonPrimitive("TASK_STATE_COMPLETED") }
            assertTrue(remoteTask().toString().contains("acceptance-result"))
            assertEquals(1, calls.get())
            // Verify the actual packaged process guard with a second worker on the same directory.
            val second = startWorker()
            try {
                assertTrue(second.waitFor(10, TimeUnit.SECONDS))
                assertNotEquals(0, second.exitValue())
            } finally { if (second.isAlive) second.destroyForcibly() }
            worker!!.destroy(); assertTrue(worker!!.waitFor(10, TimeUnit.SECONDS))
            worker = startWorker()
            waitFor("restart checkpoint cleared") {
                val state = Json.parseToJsonElement(data.resolve("device-tasks.json").readText()).jsonObject
                state["pending"] == null && state["active"] == null
            }
            Thread.sleep(16_000) // One full discovery/claim interval, no task replay.
            assertTrue(worker!!.isAlive)
            assertEquals(1, calls.get())
            assertEquals(JsonPrimitive("TASK_STATE_COMPLETED"), remoteTask()["status"]!!.jsonObject["state"])
        } finally {
            worker?.destroy(); if (worker?.waitFor(10, TimeUnit.SECONDS) == false) worker?.destroyForcibly()
            service.destroy(); if (!service.waitFor(10, TimeUnit.SECONDS)) service.destroyForcibly()
            model.stop(0)
            try {
                if (secretFile.isFile) {
                    val cleanup = startWorker(cleanup = true)
                    try {
                        assertTrue(cleanup.waitFor(10, TimeUnit.SECONDS), "Credential cleanup did not finish")
                        assertEquals(0, cleanup.exitValue(), "Credential cleanup failed")
                    } finally { if (cleanup.isAlive) cleanup.destroyForcibly() }
                }
            } finally { directory.deleteRecursively() }
        }
    }
}
