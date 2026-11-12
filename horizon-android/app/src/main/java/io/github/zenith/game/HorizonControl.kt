package io.github.zenith.game

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cliente do socket de controle do Horizon (`--control-socket`), a mesma API do
 * `horizon-ctl`: uma linha de texto por comando, uma linha JSON de resposta
 * (docs/horizon-ctl-commands.md). É por ela que os controles de toque, o controle
 * Bluetooth e o console falam com o jogo.
 *
 * Cada conexão espera a resposta antes do próximo comando, então os comandos vão
 * por pistas separadas:
 * - [buttons]: teclas e botões, em ordem, sem perder nenhum;
 * - olhar (`mouse-move`) e andar (`pad stick left`): só o valor mais recente
 *   importa, então se acumulam enquanto o anterior não foi respondido;
 * - [query]: perguntas com resposta (estado da tela, console).
 */
class HorizonControl(private val socket: File) {
    @Volatile var connected = false
        private set
    @Volatile private var closed = false

    private val lanes = mutableListOf<Lane>()

    /** Comandos discretos, em ordem. */
    private val buttonQueue = LinkedBlockingQueue<String>()
    private val queryQueue = LinkedBlockingQueue<Pair<String, (JSONObject?) -> Unit>>()

    private val lookLock = Object()
    private var lookX = 0.0
    private var lookY = 0.0

    private val stickLock = Object()
    private var stick: Pair<Float, Float>? = null
    private var stickSent = 0f to 0f

    private val rightStickLock = Object()
    private var rightStick: Pair<Float, Float>? = null

    fun start() {
        lanes += Lane("buttons") { buttonQueue.poll(250, TimeUnit.MILLISECONDS)?.let { it to null } }
        lanes += Lane("look") {
            synchronized(lookLock) {
                if (lookX == 0.0 && lookY == 0.0) lookLock.wait(250)
                if (lookX == 0.0 && lookY == 0.0) null
                else ("mouse-move %.2f %.2f".format(java.util.Locale.ROOT, lookX, lookY) to null).also { lookX = 0.0; lookY = 0.0 }
            }
        }
        lanes += Lane("move") {
            synchronized(stickLock) {
                if (stick == null) stickLock.wait(250)
                val s = stick ?: return@Lane null
                stick = null
                if (s == stickSent) null
                else {
                    stickSent = s
                    "pad stick left %.3f %.3f".format(java.util.Locale.ROOT, s.first, s.second) to null
                }
            }
        }
        lanes += Lane("pad-right") {
            synchronized(rightStickLock) {
                if (rightStick == null) rightStickLock.wait(250)
                val s = rightStick ?: return@Lane null
                rightStick = null
                "pad stick right %.3f %.3f".format(java.util.Locale.ROOT, s.first, s.second) to null
            }
        }
        lanes += Lane("query") { queryQueue.poll(250, TimeUnit.MILLISECONDS)?.let { it.first to it.second } }
        lanes.forEach { it.start() }
    }

    fun close() {
        closed = true
        lanes.forEach { it.close() }
    }

    /** Um comando em ordem (tecla, botão, atalho). */
    fun send(command: String) {
        if (command.isNotBlank()) buttonQueue.offer(command.trimEnd('\n', '\r'))
    }

    /** Movimento de olhar, em "pixels de mouse"; acumula até ser enviado. */
    fun look(dx: Double, dy: Double) {
        synchronized(lookLock) {
            lookX += dx; lookY += dy
            lookLock.notifyAll()
        }
    }

    /** O analógico esquerdo do pad virtual: -1..1, para cima positivo. */
    fun moveStick(x: Float, y: Float) {
        synchronized(stickLock) {
            stick = x to y
            stickLock.notifyAll()
        }
    }

    fun rightStick(x: Float, y: Float) {
        synchronized(rightStickLock) {
            rightStick = x to y
            rightStickLock.notifyAll()
        }
    }

    /** Pergunta ao jogo; [reply] recebe o JSON (null se o jogo não respondeu). */
    fun query(command: String, reply: (JSONObject?) -> Unit) {
        queryQueue.offer(command to reply)
    }

    /** Uma pista: uma conexão e uma thread que manda o próximo comando que [next] der. */
    private inner class Lane(
        private val name: String,
        private val next: () -> Pair<String, ((JSONObject?) -> Unit)?>?,
    ) {
        private var socketConn: LocalSocket? = null
        private var out: OutputStream? = null
        private var reader: BufferedReader? = null
        private lateinit var worker: Thread

        fun start() {
            worker = thread(name = "horizon-control-$name", isDaemon = true) { loop() }
        }

        fun close() {
            runCatching { socketConn?.close() }
            worker.interrupt()
        }

        private fun connect(): Boolean {
            if (socketConn != null) return true
            if (!socket.exists()) return false
            return try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                socketConn = s
                out = s.outputStream
                reader = BufferedReader(InputStreamReader(s.inputStream))
                connected = true
                true
            } catch (e: Exception) {
                false
            }
        }

        private fun drop() {
            runCatching { socketConn?.close() }
            socketConn = null; out = null; reader = null
            connected = false
        }

        private fun loop() {
            while (!closed) {
                if (!connect()) {
                    try { Thread.sleep(400) } catch (_: InterruptedException) { return }
                    continue
                }
                val item = try { next() } catch (_: InterruptedException) { return } ?: continue
                val (line, reply) = item
                try {
                    out!!.write((line.replace('\n', ' ') + "\n").toByteArray())
                    out!!.flush()
                    val text = reader!!.readLine()
                    if (text == null) {
                        drop(); reply?.invoke(null); continue
                    }
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json?.has("error") == true && name != "query") {
                        Log.d(TAG, "$line -> ${json.optString("error")}")
                    }
                    reply?.invoke(json)
                } catch (e: Exception) {
                    drop()
                    reply?.invoke(null)
                }
            }
            drop()
        }
    }

    companion object {
        const val TAG = "HorizonControl"
    }
}
