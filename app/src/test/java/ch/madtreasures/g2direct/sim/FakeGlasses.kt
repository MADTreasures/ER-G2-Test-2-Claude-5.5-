package ch.madtreasures.g2direct.sim

import ch.madtreasures.g2direct.ble.ArmLink
import ch.madtreasures.g2direct.ble.ArmTarget
import ch.madtreasures.g2direct.ble.EngineEnv
import ch.madtreasures.g2direct.ble.G2Link
import ch.madtreasures.g2direct.ble.LinkListener
import ch.madtreasures.g2direct.ble.LinkState
import ch.madtreasures.g2direct.ble.Side
import ch.madtreasures.g2direct.protocol.G2Font
import ch.madtreasures.g2direct.protocol.G2Frame
import ch.madtreasures.g2direct.protocol.G2Inbound
import ch.madtreasures.g2direct.protocol.G2Reassembler
import ch.madtreasures.g2direct.protocol.G2RxResult
import ch.madtreasures.g2direct.protocol.ProtoMessage
import ch.madtreasures.g2direct.protocol.ProtoWriter
import ch.madtreasures.g2direct.protocol.ServiceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Protocol-level simulation of a pair of G2 glasses, built from the behaviour documented by
 * the reference implementations (MentraOS, g2-kit, ffs-os, firmware decompilation notes).
 *
 * It is NOT the real firmware: it answers the way the documentation says the firmware
 * answers and enforces the documented limits, so the tests catch framing, sequencing and
 * bookkeeping mistakes in the app. Rendering is modelled with the LVGL rules the cursor
 * placement relies on (27 px lines, leading ASCII spaces skipped, glyph widths from pretext).
 */
class FakeGlasses(private val scope: CoroutineScope) {

    enum class TextSemantics {
        /** content replaces everything (faceclaw's reading of the stock firmware) */
        REPLACE_ALL,
        /** content replaces [offset, offset + length) of the old text (MentraOS' observation) */
        SPLICE,
    }

    // ---- knobs ----
    var connectDelayMs = 300L
    var failConnect = HashSet<Side>()
    var answerAuth = true
    var answerCreate = true
    var ignoreCreateWhenRegistered = true
    var sendTextAcks = true
    var textAckLatencyMs = 25L
    var imageAckLatencyMs = 60L
    var textSemantics = TextSemantics.REPLACE_ALL

    // ---- observable state ----
    data class TextBox(
        val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int,
        val border: Int, val padding: Int, val capture: Boolean, var content: String,
    )

    data class ImageBox(val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int, var bmp: ByteArray? = null)

    val texts = LinkedHashMap<Int, TextBox>()
    val images = LinkedHashMap<Int, ImageBox>()
    var pageRegistered = false
    var creates = 0
    var rebuilds = 0
    var shutdowns = 0
    val received = ArrayList<Pair<Side, G2Inbound>>()
    val violations = ArrayList<String>()
    val links = HashMap<Side, FakeLink>()
    var textUpdates = 0
    /** Text updates received but not yet acknowledged, and the highest that count reached. */
    var textInFlight = 0
    var maxTextInFlight = 0
    var imagesCompleted = 0

    private val rx = HashMap<Side, G2Reassembler>()
    private var replySync = 0

    private class ImageTransfer(val session: Int, val total: Int, var next: Int, val data: java.io.ByteArrayOutputStream)

    private val transfers = HashMap<Int, ImageTransfer>()

    fun env(scheduler: TestCoroutineScheduler): EngineEnv = object : EngineEnv {
        override fun elapsedMs() = scheduler.currentTime
        override fun wallClockMs() = 1_760_000_000_000L + scheduler.currentTime
        override fun utcOffsetMs(epochMs: Long) = 7_200_000
        override fun log(message: String) {}
        override fun createLink(side: Side, target: ArmTarget, listener: LinkListener): ArmLink =
            FakeLink(side, listener).also { links[side] = it }
    }

    fun messages(side: Side, sid: Int): List<ProtoMessage> =
        received.filter { it.first == side && it.second.serviceId == sid }.map { ProtoMessage.parse(it.second.payload) }

    fun evenHubCommands(side: Side = Side.RIGHT): List<Int> = messages(side, ServiceId.EVEN_HUB).mapNotNull { it.int(1) }

    // =========================================================================================

    inner class FakeLink(override val side: Side, private val listener: LinkListener) : ArmLink {
        override var state = LinkState.CLOSED
        override val mtu = 247
        override val maxPacketSize = 244
        override var packetsSent = 0L
        override var packetsDropped = 0L
        var highPriorityRequests = 0

        override fun connect() {
            state = LinkState.CONNECTING
            listener.onLinkState(this, state, "verbinde")
            scope.launch {
                delay(connectDelayMs)
                if (state != LinkState.CONNECTING) return@launch
                if (side in failConnect) {
                    state = LinkState.CLOSED
                    listener.onLinkState(this@FakeLink, state, "fehlgeschlagen")
                    listener.onLinkFailed(this@FakeLink, "Verbindung fehlgeschlagen: 133 (simuliert)")
                    return@launch
                }
                state = LinkState.READY
                rx[side] = G2Reassembler()
                listener.onLinkState(this@FakeLink, state, "bereit (MTU 247)")
            }
        }

        override fun send(packets: List<ByteArray>): Boolean {
            if (state != LinkState.READY) return false
            for (p in packets) {
                if (p.size > maxPacketSize) violations += "$side: Paket ${p.size} B > MTU"
                packetsSent++
                onPacket(side, p)
            }
            return true
        }

        override fun requestHighPriority() {
            highPriorityRequests++
        }

        override fun close(reason: String) {
            state = LinkState.CLOSED
        }

        /** Simulates the arm going out of range. */
        fun drop(reason: String = "Verbindung getrennt: 8 (simuliert)") {
            state = LinkState.CLOSED
            listener.onLinkState(this, state, reason)
            listener.onLinkFailed(this, reason)
        }

        fun deliver(frame: ByteArray, latencyMs: Long) {
            scope.launch {
                delay(latencyMs)
                if (state == LinkState.READY) listener.onLinkData(this@FakeLink, G2Link.CHAR_NOTIFY, frame)
            }
        }
    }

    // =========================================================================================

    private fun reply(side: Side, sid: Int, payload: ByteArray, latencyMs: Long = 15L) {
        replySync = (replySync + 1) and 0xFF
        for (frame in G2Frame.encode(replySync, sid, payload, flags = 0x00)) {
            frame[1] = G2Frame.TYPE_GLASSES_TO_PHONE.toByte()
            links[side]?.deliver(frame, latencyMs)
        }
    }

    /** Sends an OS event (sys item event) from the right arm, e.g. SYSTEM_EXIT = 7. */
    fun injectSysEvent(type: Int) {
        reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build {
            int(1, 2)
            message(13) { message(3) { int(1, type) } }
        })
    }

    private fun onPacket(side: Side, packet: ByteArray) {
        if ((packet[1].toInt() and 0xFF) != G2Frame.TYPE_PHONE_TO_GLASSES) violations += "falscher Typ ${packet[1]}"
        // The Even app sets the 0x20 flag on everything except device-settings frames.
        val sid = packet[6].toInt() and 0xFF
        val expectedFlags = if (sid == ServiceId.DEVICE_SETTINGS) 0x00 else G2Frame.FLAG_RESERVE
        if ((packet[7].toInt() and 0xFF) != expectedFlags) violations += "Flags ${packet[7]} für Dienst $sid"
        when (val r = rx.getValue(side).accept(packet)) {
            is G2RxResult.Complete -> {
                if (!r.message.crcOk) violations += "$side: CRC falsch"
                received += side to r.message
                onMessage(side, r.message)
            }
            is G2RxResult.Partial -> Unit
            is G2RxResult.Rejected -> violations += "$side: Frame mit Fehlercode"
            is G2RxResult.Invalid -> violations += "$side: ${r.reason}"
        }
    }

    private fun onMessage(side: Side, m: G2Inbound) {
        val pb = ProtoMessage.parse(m.payload)
        when (m.serviceId) {
            ServiceId.DEVICE_SETTINGS -> {
                if (pb.int(1) == 4 && answerAuth) {
                    reply(side, ServiceId.DEVICE_SETTINGS, ProtoWriter.build {
                        int(1, 4); int(2, pb.int(2) ?: 0); message(3) { bool(1, true) }
                    })
                }
            }
            ServiceId.G2_SETTING -> if (pb.int(1) == 2) {
                reply(side, ServiceId.G2_SETTING, ProtoWriter.build {
                    int(1, 2); int(2, pb.int(2) ?: 0)
                    message(4) { string(5, "2.2.7.14"); string(6, "2.2.7.14"); int(12, 77); int(13, 0) }
                })
            }
            ServiceId.EVEN_HUB -> {
                if (side != Side.RIGHT) {
                    violations += "EvenHub-Nachricht an den linken Bügel (cmd ${pb.int(1)})"
                    return
                }
                onEvenHub(pb)
            }
        }
    }

    private fun onEvenHub(pb: ProtoMessage) {
        val magic = pb.int(2) ?: 0
        when (pb.int(1)) {
            0 -> {
                creates++
                if (pageRegistered && ignoreCreateWhenRegistered) return
                buildPage(pb.message(3)) ?: return
                pageRegistered = true
                if (answerCreate) {
                    reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build { int(1, 1); int(2, magic); message(4) { int(1, 0) } })
                }
            }
            7 -> {
                rebuilds++
                val ok = buildPage(pb.message(7)) != null && pageRegistered
                reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build {
                    int(1, 8); int(2, magic); message(8) { int(1, if (ok) 6 else 7) }
                })
            }
            5 -> {
                val u = pb.message(9) ?: return
                val box = texts[u.int(1) ?: -1]
                val ok = pageRegistered && box != null
                if (box == null) violations += "Text-Update für unbekannten Container ${u.int(1)}"
                else {
                    if (u.string(2) != box.name) violations += "Containername ${u.string(2)} passt nicht zu ${box.name}"
                    val content = u.string(5) ?: ""
                    val offset = u.int(3) ?: 0
                    val length = u.int(4) ?: 0
                    val contentBytes = content.toByteArray(Charsets.UTF_8)
                    if (length != contentBytes.size) violations += "contentLength $length != ${contentBytes.size}"
                    if (contentBytes.size > 2000) violations += "Text zu lang"
                    box.content = when (textSemantics) {
                        TextSemantics.REPLACE_ALL -> content
                        TextSemantics.SPLICE -> {
                            val old = box.content.toByteArray(Charsets.UTF_8)
                            val head = old.copyOfRange(0, minOf(offset, old.size))
                            val tail = if (offset + length < old.size) old.copyOfRange(offset + length, old.size) else ByteArray(0)
                            String(head + contentBytes + tail, Charsets.UTF_8)
                        }
                    }
                    textUpdates++
                }
                if (sendTextAcks) {
                    textInFlight++
                    maxTextInFlight = maxOf(maxTextInFlight, textInFlight)
                    scope.launch {
                        delay(textAckLatencyMs)
                        textInFlight--
                    }
                    reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build {
                        int(1, 6); int(2, magic); message(10) { int(1, if (ok) 8 else 9) }
                    }, textAckLatencyMs)
                }
            }
            3 -> onImageFragment(pb.message(5) ?: return)
            9 -> {
                shutdowns++
                pageRegistered = false
                texts.clear(); images.clear()
                reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build { int(1, 10); message(12) { int(1, 10) } })
            }
            12 -> reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build { int(1, 12); message(15) { int(2, 12) } })
            else -> violations += "unbekannter EvenHub-Befehl ${pb.int(1)}"
        }
    }

    private fun buildPage(body: ProtoMessage?): Unit? {
        if (body == null) {
            violations += "Seite ohne Inhalt"
            return null
        }
        val t = body.messages(3)
        val i = body.messages(4)
        val total = body.int(1) ?: 0
        if (total != t.size + i.size) violations += "containerTotalNum $total != ${t.size + i.size}"
        if (t.size > 8) violations += "zu viele Textcontainer"
        if (i.size > 4) violations += "zu viele Bildcontainer"
        if (t.count { (it.int(11) ?: 0) == 1 } != 1) violations += "nicht genau ein isEventCapture"
        texts.clear(); images.clear(); transfers.clear()
        for (m in t) {
            val name = m.string(10) ?: ""
            if (name.length > 14) violations += "Name zu lang: $name"
            val content = m.string(12) ?: ""
            if (content.length > 1000) violations += "Startinhalt zu lang"
            texts[m.int(9) ?: -1] = TextBox(
                m.int(9) ?: -1, name, m.int(1) ?: 0, m.int(2) ?: 0, m.int(3) ?: 0, m.int(4) ?: 0,
                m.int(5) ?: 0, m.int(8) ?: 0, (m.int(11) ?: 0) == 1, content,
            )
        }
        for (m in i) {
            val w = m.int(3) ?: 0
            val h = m.int(4) ?: 0
            if (w !in 20..288 || h !in 20..144) violations += "Bildgröße ${w}x$h"
            images[m.int(5) ?: -1] = ImageBox(m.int(5) ?: -1, m.string(6) ?: "", m.int(1) ?: 0, m.int(2) ?: 0, w, h)
        }
        return Unit
    }

    private fun onImageFragment(f: ProtoMessage) {
        val id = f.int(1) ?: -1
        val session = f.int(3) ?: 0
        val total = f.int(4) ?: 0
        val index = f.int(6) ?: 0
        val data = f.bytes(8) ?: ByteArray(0)
        var ok = true
        val box = images[id]
        if (box == null || f.string(2) != box.name) {
            violations += "Bild für unbekannten Container $id"
            ok = false
        }
        if ((f.int(5) ?: 0) != 0) violations += "compressMode != 0"
        if (data.size != (f.int(7) ?: -1)) violations += "Fragmentgröße stimmt nicht"
        if (data.size > 4096) violations += "Fragment > 4096 B"
        var tr = transfers[id]
        if (index == 0) {
            tr = ImageTransfer(session, total, 0, java.io.ByteArrayOutputStream())
            transfers[id] = tr
        }
        if (tr == null || tr.session != session || tr.next != index || tr.total != total) {
            violations += "Fragmentfolge verletzt (session $session index $index)"
            ok = false
        } else {
            tr.data.write(data)
            tr.next++
            if (tr.data.size() > total) ok = false
            if (tr.data.size() == total && box != null) {
                val bmp = tr.data.toByteArray()
                validateBmp(bmp, box)
                box.bmp = bmp
                imagesCompleted++
                transfers.remove(id)
            }
        }
        reply(Side.RIGHT, ServiceId.EVEN_HUB, ProtoWriter.build {
            int(1, 4)
            message(6) {
                int(1, id); string(2, box?.name ?: ""); int(3, session); int(4, total)
                int(6, index); int(7, data.size); int(8, if (ok) 4 else 5)
            }
        }, imageAckLatencyMs)
    }

    private fun validateBmp(bmp: ByteArray, box: ImageBox) {
        fun le32(o: Int) = (bmp[o].toInt() and 0xFF) or ((bmp[o + 1].toInt() and 0xFF) shl 8) or
            ((bmp[o + 2].toInt() and 0xFF) shl 16) or ((bmp[o + 3].toInt() and 0xFF) shl 24)
        if (bmp.size < 0x36 || bmp[0] != 'B'.code.toByte() || bmp[1] != 'M'.code.toByte()) violations += "kein BMP"
        else {
            if (bmp[28].toInt() != 4) violations += "BMP nicht 4 bpp"
            if (le32(18) != box.w || le32(22) != box.h) violations += "BMP-Größe passt nicht zum Container"
        }
    }

    // =========================================================================================
    // Rendering model

    data class Glyph(val ch: Char, val x: Int, val y: Int, val containerId: Int)

    /** Positions (left/top edge) of every non-blank glyph in the text containers. */
    fun glyphs(): List<Glyph> {
        val out = ArrayList<Glyph>()
        for (box in texts.values) {
            val ox = box.x + box.border + box.padding
            val oy = box.y + box.border + box.padding
            val avail = box.w - 2 * (box.border + box.padding)
            box.content.split('\n').forEachIndexed { row, line ->
                var x = 0
                var lineStart = true
                for (ch in line) {
                    if (lineStart && ch == ' ') continue // LVGL skips leading ASCII spaces
                    lineStart = false
                    val w = (G2Font.advance16(ch.code) ?: 0) + 8 shr 4
                    if (!ch.isWhitespace() && ch != '\u3000' && ch != '\u00A0') {
                        out += Glyph(ch, ox + x, oy + row * G2Font.LINE_HEIGHT, box.id)
                    }
                    x += w
                    if (x > avail) violations += "Zeile in ${box.name} breiter als der Container"
                }
            }
            val lines = box.content.split('\n').size
            if (box.content.isNotEmpty() && lines * G2Font.LINE_HEIGHT > box.h - 2 * (box.border + box.padding)) {
                violations += "Text in ${box.name} läuft über (${lines} Zeilen)"
            }
        }
        return out
    }
}
