package com.example.reminder.findphone

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 运行在手机上的极简 HTTP 服务器（无第三方依赖），只在局域网内使用。
 *
 *   GET  /            控制网页
 *   GET  /api/ping    识别本应用（无需 PIN，供电脑端自动搜索）
 *   GET  /api/status  查询是否正在响铃
 *   POST /api/ring    开始响铃
 *   POST /api/stop    停止响铃
 *
 * /api/ 下的接口都要求请求头 X-Pin 与手机上显示的 PIN 一致；
 * 连续输错 10 次锁定 60 秒，防止暴力猜测。
 */
class FindPhoneServer(
    private val port: Int,
    private val pin: () -> String,
    private val callback: Callback,
    private val page: String,
) {
    interface Callback {
        fun onRing()
        fun onSilence()
        fun isRingingNow(): Boolean
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private var failures = 0
    private var lockedUntil = 0L

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = ss
        thread(name = "find-phone-http", isDaemon = true) {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (e: IOException) { break }
                pool.execute { client.use { handle(it) } }
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) { }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val parts = (reader.readLine() ?: return).split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')

            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }

            val out = socket.getOutputStream()
            when {
                method == "OPTIONS" -> respond(out, 204, TEXT, "") // CORS 预检
                method == "GET" && (path == "/" || path == "/index.html") ->
                    respond(out, 200, "text/html; charset=utf-8", page)
                path.startsWith("/api/") -> api(out, method, path, headers["x-pin"])
                else -> respond(out, 404, TEXT, "not found")
            }
        } catch (_: IOException) {
            // 客户端断开或超时，忽略
        }
    }

    private fun api(out: OutputStream, method: String, path: String, pinHeader: String?) {
        // 识别接口无需 PIN：电脑网页扫描局域网时用它确认“这是我的手机”，
        // 这样扫描时不会把 PIN 发给其他设备，也不会触发错误次数锁定
        if (method == "GET" && path == "/api/ping") {
            return respond(out, 200, JSON, """{"app":"findphone"}""")
        }
        when (checkPin(pinHeader)) {
            Auth.LOCKED -> return respond(out, 429, JSON, """{"error":"locked"}""")
            Auth.WRONG -> return respond(out, 401, JSON, """{"error":"pin"}""")
            Auth.OK -> Unit
        }
        val ringing = when ("$method $path") {
            "POST /api/ring" -> { callback.onRing(); true }
            "POST /api/stop" -> { callback.onSilence(); false }
            "GET /api/status" -> callback.isRingingNow()
            else -> return respond(out, 404, JSON, """{"error":"not found"}""")
        }
        respond(out, 200, JSON, """{"ok":true,"ringing":$ringing}""")
    }

    private enum class Auth { OK, WRONG, LOCKED }

    @Synchronized
    private fun checkPin(given: String?): Auth {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return Auth.LOCKED
        val ok = given != null &&
            MessageDigest.isEqual(given.toByteArray(), pin().toByteArray()) // 常量时间比较
        if (ok) {
            failures = 0
            return Auth.OK
        }
        if (++failures >= 10) {
            failures = 0
            lockedUntil = now + 60_000
        }
        return Auth.WRONG
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${REASONS[code] ?: "OK"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            // 允许电脑上本地保存的网页（file://）跨域调用
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: X-Pin, Content-Type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private companion object {
        const val TEXT = "text/plain; charset=utf-8"
        const val JSON = "application/json; charset=utf-8"
        val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 401 to "Unauthorized",
            404 to "Not Found", 429 to "Too Many Requests",
        )
    }
}
