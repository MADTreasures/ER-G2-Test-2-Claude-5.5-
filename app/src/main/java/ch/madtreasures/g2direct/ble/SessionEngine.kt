package ch.madtreasures.g2direct.ble

import ch.madtreasures.g2direct.protocol.CursorLayers
import ch.madtreasures.g2direct.protocol.EvenHubCmd
import ch.madtreasures.g2direct.protocol.EvenHubResult
import ch.madtreasures.g2direct.protocol.G2Event
import ch.madtreasures.g2direct.protocol.G2Frame
import ch.madtreasures.g2direct.protocol.G2Messages
import ch.madtreasures.g2direct.protocol.G2Reassembler
import ch.madtreasures.g2direct.protocol.G2Responses
import ch.madtreasures.g2direct.protocol.G2RxResult
import ch.madtreasures.g2direct.protocol.ImageContainer
import ch.madtreasures.g2direct.protocol.OsEvent
import ch.madtreasures.g2direct.protocol.ServiceId
import ch.madtreasures.g2direct.protocol.TestPage
import ch.madtreasures.g2direct.protocol.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumMap
import java.util.UUID
import kotlin.math.max

/**
 * Talks to one pair of G2 glasses: connects both arms, runs the Even-compatible start-up
 * sequence, builds the test page and streams cursor updates.
 *
 * Threading: [scope] must be single-threaded and every [LinkListener] callback must arrive on
 * that same thread (the Android wrapper uses one HandlerThread for both). Only the cursor
 * target is written from other threads, guarded by [inputLock].
 *
 * Arm roles: the right arm is the command pipe. All EvenHub traffic goes to the right arm
 * only - other drivers report that EvenHub frames on the left arm wedge or even reboot the
 * firmware - and the firmware mirrors the page to the left lens.
 */
class SessionEngine(
    private val scope: CoroutineScope,
    private val env: EngineEnv,
) : LinkListener {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // ---- cursor input, written from the UI thread ----
    private val inputLock = Any()
    private var targetX = 288f
    private var targetY = 144f
    @Volatile
    private var style = CursorLayers.Style.CROSSHAIR
    private val cursorSignal = Channel<Unit>(Channel.CONFLATED)

    // ---- session-thread state ----
    private class LinkOutcome(val error: String?)

    private val links = EnumMap<Side, ArmLink>(Side::class.java)
    private val linkWaiters = EnumMap<Side, CompletableDeferred<LinkOutcome>>(Side::class.java)
    private val reassemblers = EnumMap<Side, G2Reassembler>(Side::class.java).apply {
        put(Side.LEFT, G2Reassembler()); put(Side.RIGHT, G2Reassembler())
    }
    private var sessionJob: Job? = null
    private var imagesJob: Job? = null
    private var pageBuildInProgress = false
    private val loopJobs = ArrayList<Job>()
    private var request: ConnectRequest? = null
    private var userDisconnect = false

    private var syncCounter = 0
    private var magicCounter = 0
    private var imageSession = 0

    private var textAcks = Channel<Pair<Int?, Long>>(Channel.UNLIMITED)
    private var pageAcks = Channel<G2Event.PageResult>(Channel.UNLIMITED)
    private var imageAcks = Channel<G2Event.ImageAck>(Channel.UNLIMITED)
    private var rightAuth = CompletableDeferred<Boolean>()

    private var lastPlacement: CursorLayers.Placement? = null
    private var forceRedraw = true
    private var infoDirty = true
    private var lastInfoAt = 0L
    private var infoCounter = 0
    private var lastRecoveryAt = -100_000L
    private var lastPriorityRequestAt = -100_000L
    private var lastInputEvent: Pair<Int, Long>? = null
    private val extraChannelSeen = HashSet<String>()

    // stats
    private var textSent = 0
    private var textAcked = 0
    private var textAcksReceived = 0
    private var textTimeouts = 0
    private var textRejected = 0
    private var ackMsAvg = 0.0
    private var consecutiveTimeouts = 0
    private var fixedRateMode = false
    private val updateTimes = ArrayDeque<Long>()
    private var lastStatsAt = 0L
    private var crcErrors = 0
    private var rejectedFrames = 0
    private var lastInvalidLogAt = -100_000L

    // =========================================================================================
    // Public API. Methods that touch session state hop onto [scope].
    // =========================================================================================

    fun connect(req: ConnectRequest) {
        scope.launch {
            userDisconnect = false
            request = req
            startSession(req, attempt = 0)
        }
    }

    fun disconnect() {
        scope.launch {
            userDisconnect = true
            request = null
            // Leave the glasses in their normal state: close our page before dropping the link.
            if (links[Side.RIGHT]?.state == LinkState.READY && _state.value.page != PageState.NONE) {
                send(Side.RIGHT, ServiceId.EVEN_HUB, G2Messages.shutdownPage(nextMagic()))
                delay(400)
            }
            sessionJob?.cancel()
            teardown("vom Benutzer getrennt")
            _state.update { SessionState(style = it.style, speed = it.speed) }
            log("Getrennt.")
        }
    }

    /** Rebuilds the test page and re-sends its image (menu action). */
    fun rebuildTestPage() {
        scope.launch { rebuildNow() }
    }

    fun setStyle(newStyle: CursorLayers.Style) {
        scope.launch {
            if (newStyle == style) return@launch
            style = newStyle
            _state.update { it.copy(style = newStyle) }
            clampTarget()
            // A layer's byte length depends on the style, so the page is rebuilt with empty layers.
            rebuildNow()
        }
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed.coerceIn(0.3f, 4f)) }
    }

    /** Relative cursor movement in glasses pixels. Safe to call from any thread at touch rate. */
    fun moveCursorBy(dx: Float, dy: Float) {
        val s = style
        synchronized(inputLock) {
            targetX = (targetX + dx).coerceIn(CursorLayers.minX(s).toFloat(), CursorLayers.maxX(s).toFloat())
            targetY = (targetY + dy).coerceIn(CursorLayers.minY(s).toFloat(), CursorLayers.maxY(s).toFloat())
        }
        cursorSignal.trySend(Unit)
    }

    fun centerCursor() {
        synchronized(inputLock) {
            targetX = 288f
            targetY = 144f
        }
        clampTarget()
        cursorSignal.trySend(Unit)
    }

    /** Current (unsnapped) cursor target, for tests and diagnostics. */
    fun target(): Pair<Float, Float> = synchronized(inputLock) { targetX to targetY }

    /** A finger touched the pad: keep the link in its fast mode and bring back a lost page. */
    fun onTouchStart() {
        scope.launch {
            val now = env.elapsedMs()
            if (now - lastPriorityRequestAt > 20_000) {
                // The stock firmware drops to a slow connection interval after ~60 s; ask for
                // the fast one again whenever the user starts interacting.
                lastPriorityRequestAt = now
                links.values.forEach { if (it.state == LinkState.READY) it.requestHighPriority() }
            }
            val page = _state.value.page
            if (_state.value.phase == SessionPhase.READY &&
                (page == PageState.LOST || page == PageState.HIDDEN) &&
                now - lastRecoveryAt > 2_500
            ) {
                lastRecoveryAt = now
                log("Touch bei inaktiver Seite → Seite wird neu aufgebaut")
                rebuildNow()
            }
        }
    }

    /** Bond state change reported by the platform for [address]. */
    fun onBondState(address: String, text: String, bonded: Boolean, inProgress: Boolean) {
        scope.launch {
            val side = links.entries.firstOrNull { (_, link) -> linkAddress(link) == address }?.key
                ?: return@launch
            updateArm(side) { it.copy(bonded = bonded) }
            if (inProgress) notice(Severity.INFO, "${side.label}: $text")
            log("${side.label}: $text")
        }
    }

    // =========================================================================================
    // Session flow
    // =========================================================================================

    private fun startSession(req: ConnectRequest, attempt: Int) {
        sessionJob?.cancel()
        teardown(null)
        resetStats()
        _state.update {
            SessionState(
                phase = if (attempt == 0) SessionPhase.CONNECTING else SessionPhase.RECONNECTING,
                title = req.title, style = it.style, speed = it.speed, reconnectAttempt = attempt,
                notice = if (attempt == 0) null else it.notice,
            )
        }
        sessionJob = scope.launch {
            try {
                runSession(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionFailure) {
                val next = attempt + 1
                if (attempt > 0 && next <= MAX_RECONNECTS && !userDisconnect && request === req) {
                    teardown(null)
                    notice(Severity.ERROR, "Neuer Versuch $attempt/$MAX_RECONNECTS gescheitert: ${e.message}")
                    _state.update { it.copy(phase = SessionPhase.RECONNECTING, reconnectAttempt = next) }
                    scope.launch {
                        delay(2_000L * next)
                        if (request === req && !userDisconnect) startSession(req, next)
                    }
                } else {
                    fail(e.message ?: "Fehler")
                }
            } catch (e: Exception) {
                fail("Interner Fehler: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private class SessionFailure(message: String) : Exception(message)

    private suspend fun runSession(req: ConnectRequest) {
        log("Verbinde ${req.title}: R=${req.right.address} L=${req.left?.address ?: "–"}")
        openLink(Side.RIGHT, req.right)
            ?: throw SessionFailure("Rechter Bügel: ${_state.value.right.detail}")
        if (req.left != null) {
            if (openLink(Side.LEFT, req.left) == null) {
                notice(Severity.WARN, "Linker Bügel nicht verbunden (${_state.value.left.detail}) – weiter nur mit rechts")
            }
        } else {
            notice(Severity.WARN, "Kein linker Bügel gewählt – nur rechts verbunden")
        }

        _state.update { it.copy(phase = SessionPhase.INITIALIZING) }
        runStartupSequence()

        _state.update { it.copy(phase = SessionPhase.CREATING_PAGE) }
        if (!buildPage(preferRebuild = false)) {
            throw SessionFailure("Brille hat die Testseite abgelehnt – Details im Protokoll")
        }
        startLoops()
        _state.update { it.copy(phase = SessionPhase.READY, reconnectAttempt = 0) }
        log("Bereit. Cursor über das Uhr-Display bewegen.")
        startImages()
    }

    /**
     * Connects one arm. Android's first connectGatt after a scan fails with status 133 quite
     * often while the second attempt works, so transient GATT errors get one more try.
     */
    private suspend fun openLink(side: Side, target: ArmTarget): ArmLink? {
        val first = openLinkOnce(side, target)
        if (first != null) return first
        val detail = (if (side == Side.LEFT) _state.value.left else _state.value.right).detail
        if (TRANSIENT_GATT_ERRORS.none { detail.contains(it) }) return null
        log("${side.label}: neuer Verbindungsversuch nach vorübergehendem Fehler")
        delay(1_500)
        return openLinkOnce(side, target)
    }

    private suspend fun openLinkOnce(side: Side, target: ArmTarget): ArmLink? {
        val waiter = CompletableDeferred<LinkOutcome>()
        linkWaiters[side] = waiter
        val link = env.createLink(side, target, this)
        links[side] = link
        updateArm(side) {
            ArmUi(side, name = target.name, address = target.address, phase = ArmPhase.CONNECTING,
                detail = "verbinde…", bonded = target.bonded)
        }
        link.connect()
        // withTimeoutOrNull yields null only on timeout; success is LinkOutcome(null).
        val outcome = withTimeoutOrNull(LINK_TIMEOUT_MS) { waiter.await() }
            ?: LinkOutcome("Zeitüberschreitung beim Verbinden")
        linkWaiters.remove(side)
        val error = outcome.error ?: return link
        link.close(error)
        if (links[side] === link) links.remove(side)
        updateArm(side) { it.copy(phase = ArmPhase.FAILED, detail = error) }
        log("${side.label}: $error")
        return null
    }

    private suspend fun runStartupSequence() {
        val hasLeft = links[Side.LEFT]?.state == LinkState.READY
        rightAuth = CompletableDeferred()
        val started = env.elapsedMs()
        // Same order and 200 ms spacing as MentraOS G2.kt runAuthSequence (production code).
        if (hasLeft) send(Side.LEFT, ServiceId.DEVICE_SETTINGS, G2Messages.auth(nextMagic()))
        delay(200)
        send(Side.RIGHT, ServiceId.DEVICE_SETTINGS, G2Messages.auth(nextMagic()))
        delay(200)
        send(Side.RIGHT, ServiceId.DEVICE_SETTINGS, G2Messages.pipeRoleChange(nextMagic()))
        delay(200)
        val now = env.wallClockMs()
        val offset = env.utcOffsetMs(now)
        send(Side.RIGHT, ServiceId.DEVICE_SETTINGS, G2Messages.timeSync(nextMagic(), now, offset))
        if (hasLeft) send(Side.LEFT, ServiceId.DEVICE_SETTINGS, G2Messages.timeSync(nextMagic(), now, offset))
        delay(200)
        send(Side.RIGHT, ServiceId.ONBOARDING, G2Messages.onboardingFinished(nextMagic()))
        delay(200)
        send(Side.RIGHT, ServiceId.GESTURE_CTRL, G2Messages.gestureControlInit(nextMagic()))
        delay(200)
        send(Side.RIGHT, ServiceId.G2_SETTING, G2Messages.requestDeviceInfo(nextMagic()))
        val remaining = AUTH_WAIT_MS - (env.elapsedMs() - started)
        when (withTimeoutOrNull(max(remaining, 300L)) { rightAuth.await() }) {
            true -> log("Anmeldung bestätigt (rechts${if (_state.value.left.authenticated) " + links" else ""})")
            false -> notice(Severity.WARN, "Brille meldet Anmeldung abgelehnt – versuche trotzdem weiter")
            null -> notice(Severity.WARN, "Keine Anmelde-Bestätigung vom rechten Bügel – versuche trotzdem weiter")
        }
    }

    private suspend fun rebuildNow() {
        if (links[Side.RIGHT]?.state != LinkState.READY) return
        val page = _state.value.page
        if (buildPage(preferRebuild = page != PageState.LOST && page != PageState.NONE)) startImages()
    }

    /**
     * CREATE the page (first time per glasses session) or REBUILD it. The firmware only
     * accepts CREATE while no EvenHub page is registered and does not always answer a second
     * CREATE, so a missing or negative answer falls back to REBUILD.
     */
    private suspend fun buildPage(preferRebuild: Boolean): Boolean {
        if (pageBuildInProgress) {
            log("Seitenaufbau läuft bereits")
            return false
        }
        pageBuildInProgress = true
        try {
            return buildPageInternal(preferRebuild)
        } finally {
            pageBuildInProgress = false
        }
    }

    private suspend fun buildPageInternal(preferRebuild: Boolean): Boolean {
        imagesJob?.cancel()
        _state.update { it.copy(page = PageState.CREATING) }
        drain(pageAcks)
        lastPlacement = null
        forceRedraw = true
        infoDirty = true
        val (cx, cy) = target()
        val texts = TestPage.textContainers(cx.toInt(), cy.toInt())
        val images = TestPage.images

        if (!preferRebuild) {
            send(Side.RIGHT, ServiceId.EVEN_HUB, G2Messages.createPage(nextMagic(), texts, images))
            val res = awaitPageResult(EvenHubCmd.RESPONSE_CREATE_STARTUP_PAGE, PAGE_ACK_TIMEOUT_MS)
            if (res != null && (res.code ?: 0) == EvenHubResult.CREATE_SUCCESS) {
                pageReady(confirmed = true, how = "CREATE")
                return true
            }
            if (res != null) {
                log("CREATE abgelehnt: ${EvenHubResult.describe(res.code ?: -1)} – versuche REBUILD")
            } else {
                log("Keine Antwort auf CREATE (Seite evtl. schon registriert) – versuche REBUILD")
            }
        }
        send(Side.RIGHT, ServiceId.EVEN_HUB, G2Messages.rebuildPage(nextMagic(), texts, images))
        val res = awaitPageResult(EvenHubCmd.RESPONSE_REBUILD_PAGE, PAGE_ACK_TIMEOUT_MS)
        return when {
            res == null -> {
                pageReady(confirmed = false, how = "REBUILD")
                notice(Severity.WARN, "Brille hat den Seitenaufbau nicht bestätigt – Anzeige prüfen")
                true
            }
            res.code == EvenHubResult.REBUILD_SUCCESS -> {
                pageReady(confirmed = true, how = "REBUILD")
                true
            }
            else -> {
                _state.update { it.copy(page = PageState.LOST) }
                notice(Severity.ERROR, "Seitenaufbau abgelehnt: ${EvenHubResult.describe(res.code ?: -1)}")
                false
            }
        }
    }

    private fun pageReady(confirmed: Boolean, how: String) {
        _state.update { it.copy(page = if (confirmed) PageState.ACTIVE else PageState.UNCONFIRMED) }
        log("Testseite per $how ${if (confirmed) "bestätigt" else "gesendet (ohne Bestätigung)"}")
        cursorSignal.trySend(Unit)
    }

    private fun startImages() {
        imagesJob?.cancel()
        imagesJob = scope.launch { sendImages() }
    }

    private suspend fun sendImages() {
        _state.update { it.copy(imageStatus = "warte…") }
        // ffs-os/gateway: the firmware needs ~700 ms after a create/rebuild before it accepts pixels.
        delay(IMAGE_SETTLE_MS)
        var ok = 0
        for (image in TestPage.images) {
            val bmp = TestPage.imageBitmap(image).toBmp()
            _state.update { it.copy(imageStatus = "sende ${image.name} (${bmp.size} B)…") }
            // g2-kit: the first image stream after a page create can be dropped although it is
            // acknowledged - so every image goes out twice.
            val first = sendImage(image, bmp)
            val second = sendImage(image, bmp)
            if (first || second) ok++
        }
        val n = TestPage.images.size
        _state.update { it.copy(imageStatus = if (ok == n) "$ok/$n bestätigt" else "fehlgeschlagen ($ok/$n)") }
        if (ok < n) notice(Severity.WARN, "Bildübertragung nicht bestätigt – Text/Cursor laufen trotzdem")
    }

    private suspend fun sendImage(image: ImageContainer, bmp: ByteArray): Boolean {
        for (attempt in 1..3) {
            // Bump by 2: g2-kit saw an adjacent session id inherit a failed transfer.
            imageSession = (imageSession + 2) and 0xFF
            val session = imageSession
            drain(imageAcks)
            var offset = 0
            var index = 0
            var ok = true
            while (offset < bmp.size) {
                val end = minOf(offset + IMAGE_FRAGMENT, bmp.size)
                val fragment = bmp.copyOfRange(offset, end)
                val msg = G2Messages.imageFragment(nextMagic(), image.id, image.name, session, bmp.size, index, fragment)
                if (!send(Side.RIGHT, ServiceId.EVEN_HUB, msg)) return false
                val ack = awaitImageAck(session, index, IMAGE_ACK_TIMEOUT_MS)
                if (ack == null || ack.code != EvenHubResult.IMAGE_SUCCESS) {
                    log(
                        "Bild ${image.name} Fragment $index: " +
                            (ack?.let { EvenHubResult.describe(it.code ?: -1) } ?: "keine Bestätigung") +
                            " (Versuch $attempt/3)"
                    )
                    ok = false
                    break
                }
                offset = end
                index++
            }
            if (ok) {
                log("Bild ${image.name} übertragen ($index Fragment${if (index == 1) "" else "e"}, ${bmp.size} B)")
                return true
            }
            delay(300)
        }
        return false
    }

    private fun startLoops() {
        loopJobs.forEach { it.cancel() }
        loopJobs.clear()
        loopJobs += scope.launch { heartbeatLoop() }
        loopJobs += scope.launch { cursorLoop() }
    }

    private suspend fun heartbeatLoop() {
        var n = 0
        while (rightReady()) {
            delay(HEARTBEAT_MS)
            if (!rightReady()) break
            send(Side.RIGHT, ServiceId.EVEN_HUB, G2Messages.evenHubHeartbeat(nextMagic()))
            send(Side.RIGHT, ServiceId.DEVICE_SETTINGS, G2Messages.deviceHeartbeat(nextMagic()))
            if (++n % 12 == 0) send(Side.RIGHT, ServiceId.G2_SETTING, G2Messages.requestDeviceInfo(nextMagic()))
            publishStats()
        }
    }

    private suspend fun cursorLoop() {
        var lastUpdateAt = -100_000L
        while (rightReady()) {
            cursorSignal.receive()
            val page = _state.value.page
            if (page != PageState.ACTIVE && page != PageState.UNCONFIRMED) continue

            // At most ~25 updates/s, or a fixed 16/s when the firmware never acknowledges text.
            val minGap = if (fixedRateMode) FIXED_RATE_GAP_MS else MIN_UPDATE_GAP_MS
            val sinceLast = env.elapsedMs() - lastUpdateAt
            if (sinceLast < minGap) delay(minGap - sinceLast)

            val (x, y) = target()
            val s = style
            val placement = CursorLayers.place(s, x, y)
            val previous = lastPlacement
            if (placement != previous || forceRedraw) {
                forceRedraw = false
                val updates = ArrayList<Pair<Int, String>>(2)
                // New position first, then blank the old layer: a moment with two cursors
                // reads better than a moment with none.
                updates += placement.layer to CursorLayers.content(s, placement.layer, placement)
                if (previous != null && previous.layer != placement.layer) {
                    updates += previous.layer to CursorLayers.content(s, previous.layer, null)
                }
                lastPlacement = placement
                drain(textAcks)
                // The pacing gap counts from the send, so the rate is max(1 / gap, 1 / ack latency).
                lastUpdateAt = env.elapsedMs()
                for ((layer, content) in updates) sendText(TestPage.layerId(layer), TestPage.layerName(layer), content)
                awaitTextAcks(updates.size)
                noteUpdate(env.elapsedMs())
                infoDirty = true
                _state.update { it.copy(cursorX = placement.x, cursorY = placement.y) }
            }

            if (infoDirty) {
                val now = env.elapsedMs()
                if (now - lastInfoAt >= INFO_INTERVAL_MS) {
                    infoDirty = false
                    lastInfoAt = now
                    val p = lastPlacement
                    drain(textAcks)
                    sendText(TestPage.FRAME_ID, TestPage.FRAME_NAME, TestPage.frameText(p?.x ?: 288, p?.y ?: 144, ++infoCounter))
                    awaitTextAcks(1)
                } else {
                    val wait = INFO_INTERVAL_MS - (now - lastInfoAt)
                    scope.launch {
                        delay(wait)
                        cursorSignal.trySend(Unit)
                    }
                }
            }
        }
    }

    private fun rightReady(): Boolean = links[Side.RIGHT]?.state == LinkState.READY

    // =========================================================================================
    // Sending
    // =========================================================================================

    private fun nextSync(): Int {
        syncCounter = (syncCounter + 1) and 0xFF
        return syncCounter
    }

    /** MagicRandom is effectively a uint8 in the firmware (g2-kit); 0 is avoided so echoes stay visible. */
    private fun nextMagic(): Int {
        magicCounter = magicCounter % 255 + 1
        return magicCounter
    }

    private fun send(side: Side, serviceId: Int, payload: ByteArray): Boolean {
        val link = links[side] ?: return false
        if (link.state != LinkState.READY) return false
        // The Even app sends device-settings frames without the reserve flag (MentraOS does the same).
        val flags = if (serviceId == ServiceId.DEVICE_SETTINGS) 0x00 else G2Frame.FLAG_RESERVE
        return link.send(G2Frame.encode(nextSync(), serviceId, payload, flags, link.maxPacketSize))
    }

    private fun sendText(containerId: Int, name: String, content: String) {
        // Callers keep every container's content at a constant byte length, so "replace this
        // many bytes" and "the new text is this long" mean the same thing (see CursorLayers).
        val len = content.toByteArray(Charsets.UTF_8).size
        if (send(Side.RIGHT, ServiceId.EVEN_HUB, G2Messages.updateText(nextMagic(), containerId, name, content, len))) {
            textSent++
        }
    }

    private suspend fun awaitTextAcks(count: Int) {
        val sentAt = env.elapsedMs()
        repeat(count) {
            val timeout = if (fixedRateMode) 1L else TEXT_ACK_TIMEOUT_MS
            val remaining = timeout - (env.elapsedMs() - sentAt)
            val ack = if (remaining > 0) {
                withTimeoutOrNull(remaining) { textAcks.receive() }
            } else {
                textAcks.tryReceive().getOrNull()
            }
            if (ack == null) {
                if (!fixedRateMode) {
                    textTimeouts++
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 4 && textAcksReceived == 0) {
                        fixedRateMode = true
                        notice(Severity.WARN, "Brille bestätigt Text-Updates nicht – Festtakt 16/s")
                    }
                }
                return
            }
            consecutiveTimeouts = 0
            val (code, at) = ack
            textAcked++
            val ms = (at - sentAt).coerceAtLeast(0)
            ackMsAvg = if (ackMsAvg == 0.0) ms.toDouble() else ackMsAvg * 0.85 + ms * 0.15
            if (code != null && code != EvenHubResult.TEXT_SUCCESS) {
                textRejected++
                if (code == EvenHubResult.TEXT_FAILED) pageLost("Brille lehnt Text-Update ab (Seite nicht aktiv)")
            }
        }
    }

    private suspend fun awaitPageResult(cmd: Int, timeoutMs: Long): G2Event.PageResult? =
        withTimeoutOrNull(timeoutMs) {
            var result: G2Event.PageResult? = null
            while (result == null) {
                val r = pageAcks.receive()
                if (r.cmd == cmd) result = r
            }
            result
        }

    private suspend fun awaitImageAck(session: Int, index: Int, timeoutMs: Long): G2Event.ImageAck? =
        withTimeoutOrNull(timeoutMs) {
            var result: G2Event.ImageAck? = null
            while (result == null) {
                val a = imageAcks.receive()
                if ((a.sessionId == null || a.sessionId == session) && a.fragmentIndex == index) result = a
            }
            result
        }

    private fun <T> drain(ch: Channel<T>) {
        while (ch.tryReceive().isSuccess) Unit
    }

    // =========================================================================================
    // Link callbacks (session thread)
    // =========================================================================================

    override fun onLinkState(link: ArmLink, state: LinkState, detail: String) {
        if (links[link.side] !== link) return
        val phase = when (state) {
            LinkState.CONNECTING -> ArmPhase.CONNECTING
            LinkState.CONNECTED, LinkState.DISCOVERING, LinkState.SUBSCRIBING -> ArmPhase.SETUP
            LinkState.READY -> ArmPhase.READY
            LinkState.CLOSED -> ArmPhase.OFF
        }
        updateArm(link.side) { it.copy(phase = phase, detail = detail, mtu = link.mtu) }
        if (state == LinkState.READY) {
            reassemblers.getValue(link.side).reset()
            log("${link.side.label}: $detail")
            linkWaiters[link.side]?.complete(LinkOutcome(null))
        }
    }

    override fun onLinkFailed(link: ArmLink, reason: String) {
        if (links[link.side] !== link) return
        val waiter = linkWaiters[link.side]
        if (waiter != null && !waiter.isCompleted) {
            waiter.complete(LinkOutcome(reason))
            return
        }
        links.remove(link.side)
        updateArm(link.side) { it.copy(phase = ArmPhase.FAILED, detail = reason, authenticated = false) }
        log("${link.side.label}: $reason")
        if (link.side == Side.LEFT) {
            notice(Severity.WARN, "Linker Bügel getrennt: $reason – rechts läuft weiter")
            return
        }
        // Right arm lost: the session is broken.
        val wasReady = _state.value.phase == SessionPhase.READY
        sessionJob?.cancel()
        val req = request
        teardown(null)
        if (userDisconnect || req == null) return
        val attempt = _state.value.reconnectAttempt + 1
        if (wasReady && attempt <= MAX_RECONNECTS) {
            notice(Severity.ERROR, "Verbindung zum rechten Bügel verloren: $reason")
            _state.update { it.copy(phase = SessionPhase.RECONNECTING, reconnectAttempt = attempt, page = PageState.NONE) }
            scope.launch {
                delay(2_000L * attempt)
                if (request === req && !userDisconnect) startSession(req, attempt)
            }
        } else {
            fail("Rechter Bügel: $reason")
        }
    }

    override fun onLinkData(link: ArmLink, characteristic: UUID, data: ByteArray) {
        if (links[link.side] !== link) return
        if (characteristic != G2Link.CHAR_NOTIFY) {
            val key = "${link.side.short}:${characteristic.toString().substring(32)}"
            if (extraChannelSeen.add(key)) log("Daten auf Nebenkanal $key (${data.size} B) – ignoriert")
            return
        }
        when (val r = reassemblers.getValue(link.side).accept(data)) {
            is G2RxResult.Complete -> {
                if (!r.message.crcOk) crcErrors++
                handleEvent(link.side, r.message.serviceId, G2Responses.decode(r.message))
            }
            is G2RxResult.Partial -> Unit
            is G2RxResult.Rejected -> {
                rejectedFrames++
                log("${link.side.short}: Brille meldet Fehler ${r.resultCode} für ${ServiceId.name(r.serviceId)} (Frame ${r.raw.toHex(16)})")
            }
            is G2RxResult.Invalid -> {
                val now = env.elapsedMs()
                if (now - lastInvalidLogAt > 2_000) {
                    lastInvalidLogAt = now
                    log("${link.side.short}: unlesbares Paket (${r.reason}): ${r.raw.toHex(20)}")
                }
            }
        }
    }

    override fun onLinkLog(link: ArmLink, message: String) = log("${link.side.short}: $message")

    private fun handleEvent(side: Side, serviceId: Int, event: G2Event) {
        when (event) {
            is G2Event.Auth -> {
                updateArm(side) { it.copy(authenticated = event.ok) }
                if (side == Side.RIGHT) rightAuth.complete(event.ok)
            }
            is G2Event.PageResult -> {
                if (side != Side.RIGHT) return
                when (event.cmd) {
                    EvenHubCmd.RESPONSE_TEXT_DATA -> {
                        textAcksReceived++
                        if (fixedRateMode) {
                            fixedRateMode = false
                            log("Text-Bestätigungen kommen wieder – adaptive Taktung")
                        }
                        textAcks.trySend(event.code to env.elapsedMs())
                    }
                    EvenHubCmd.RESPONSE_SHUTDOWN_PAGE -> {
                        log("Brille: Seite geschlossen (${EvenHubResult.describe(event.code ?: -1)})")
                        if (_state.value.page != PageState.CREATING) pageLost("Seite wurde geschlossen")
                    }
                    else -> pageAcks.trySend(event)
                }
            }
            is G2Event.ImageAck -> if (side == Side.RIGHT) imageAcks.trySend(event)
            is G2Event.HeartbeatAck -> Unit
            is G2Event.Input -> handleInput(side, event)
            is G2Event.DeviceInfo -> _state.update {
                it.copy(
                    battery = event.batteryPercent ?: it.battery,
                    charging = event.charging ?: it.charging,
                    firmware = event.rightFirmware ?: event.leftFirmware ?: it.firmware,
                )
            }
            is G2Event.Other -> {
                if (serviceId == ServiceId.GESTURE_CTRL) log("${side.short}: Gesten-Ereignis ${event.summary}")
            }
        }
    }

    private fun handleInput(side: Side, event: G2Event.Input) {
        // Both arms may deliver the same event; drop duplicates within 300 ms.
        val now = env.elapsedMs()
        val last = lastInputEvent
        if (last != null && last.first == event.type && now - last.second < 300) return
        lastInputEvent = event.type to now
        val source = if (event.eventSource == 2) ", Ring" else ""
        val text = "${OsEvent.describe(event.type)} (${side.short}$source)"
        _state.update { it.copy(lastGlassesInput = text) }
        log("Brille: $text")
        when (event.type) {
            OsEvent.SYSTEM_EXIT, OsEvent.ABNORMAL_EXIT ->
                pageLost("Brille hat die Testseite beendet (${OsEvent.describe(event.type)})")
            OsEvent.FOREGROUND_EXIT -> {
                _state.update { it.copy(page = PageState.HIDDEN) }
                notice(Severity.WARN, "Testbild im Hintergrund – Touchpad berühren zum Zurückholen")
            }
            OsEvent.FOREGROUND_ENTER -> if (_state.value.page == PageState.HIDDEN) {
                _state.update { it.copy(page = PageState.ACTIVE) }
                forceRedraw = true
                cursorSignal.trySend(Unit)
            }
        }
    }

    private fun pageLost(reason: String) {
        if (_state.value.page == PageState.LOST) return
        _state.update { it.copy(page = PageState.LOST) }
        log(reason)
        // Short and action first: the touchpad screen only has room for about three lines.
        notice(Severity.WARN, "Testbild geschlossen – Touchpad berühren zum Neuaufbau")
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private fun clampTarget() {
        val s = style
        synchronized(inputLock) {
            targetX = targetX.coerceIn(CursorLayers.minX(s).toFloat(), CursorLayers.maxX(s).toFloat())
            targetY = targetY.coerceIn(CursorLayers.minY(s).toFloat(), CursorLayers.maxY(s).toFloat())
        }
    }

    private fun linkAddress(link: ArmLink): String? =
        (if (link.side == Side.LEFT) _state.value.left else _state.value.right).address

    private fun teardown(reason: String?) {
        imagesJob?.cancel()
        imagesJob = null
        pageBuildInProgress = false
        loopJobs.forEach { it.cancel() }
        loopJobs.clear()
        for (waiter in linkWaiters.values) if (!waiter.isCompleted) waiter.complete(LinkOutcome("abgebrochen"))
        linkWaiters.clear()
        val open = links.values.toList()
        links.clear()
        for (link in open) {
            link.close(reason ?: "geschlossen")
            updateArm(link.side) {
                if (it.phase == ArmPhase.FAILED) it else it.copy(phase = ArmPhase.OFF, detail = reason ?: "getrennt", authenticated = false)
            }
        }
        reassemblers.values.forEach { it.reset() }
        textAcks = Channel(Channel.UNLIMITED)
        pageAcks = Channel(Channel.UNLIMITED)
        imageAcks = Channel(Channel.UNLIMITED)
        lastPlacement = null
        forceRedraw = true
    }

    private fun fail(reason: String) {
        teardown(reason)
        _state.update { it.copy(phase = SessionPhase.FAILED, page = PageState.NONE) }
        notice(Severity.ERROR, reason)
    }

    private fun resetStats() {
        textSent = 0; textAcked = 0; textAcksReceived = 0; textTimeouts = 0; textRejected = 0
        ackMsAvg = 0.0; consecutiveTimeouts = 0; fixedRateMode = false
        updateTimes.clear(); crcErrors = 0; rejectedFrames = 0
        extraChannelSeen.clear()
        lastInputEvent = null
    }

    private fun noteUpdate(at: Long) {
        updateTimes.addLast(at)
        while (updateTimes.isNotEmpty() && at - updateTimes.first() > 2_000) updateTimes.removeFirst()
        if (at - lastStatsAt >= 500) {
            lastStatsAt = at
            publishStats()
        }
    }

    private fun publishStats() {
        val now = env.elapsedMs()
        while (updateTimes.isNotEmpty() && now - updateTimes.first() > 2_000) updateTimes.removeFirst()
        val right = links[Side.RIGHT]
        _state.update {
            it.copy(
                stats = Stats(
                    textSent = textSent, textAcked = textAcked, textTimeouts = textTimeouts,
                    textRejected = textRejected, ackMsAvg = ackMsAvg.toInt(),
                    updatesPerSecond = updateTimes.size / 2f, fixedRateMode = fixedRateMode,
                    packetsSent = right?.packetsSent ?: it.stats.packetsSent,
                    packetsDropped = right?.packetsDropped ?: it.stats.packetsDropped,
                    crcErrors = crcErrors, rejectedFrames = rejectedFrames,
                )
            )
        }
    }

    private fun updateArm(side: Side, f: (ArmUi) -> ArmUi) {
        _state.update { if (side == Side.LEFT) it.copy(left = f(it.left)) else it.copy(right = f(it.right)) }
    }

    private fun notice(severity: Severity, text: String) {
        _state.update { it.copy(notice = Notice(text, severity, env.wallClockMs())) }
        log(
            when (severity) {
                Severity.ERROR -> "FEHLER: "
                Severity.WARN -> "Warnung: "
                Severity.INFO -> ""
            } + text
        )
    }

    fun log(message: String) {
        env.log(message)
        val line = formatTime(env.wallClockMs(), env.utcOffsetMs(env.wallClockMs())) + "  " + message
        _log.update { (listOf(line) + it).take(LOG_LINES) }
    }

    private fun formatTime(epochMs: Long, offsetMs: Int): String {
        val t = Math.floorMod(epochMs + offsetMs, 86_400_000L)
        return "%02d:%02d:%02d.%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60, t % 1_000)
    }

    companion object {
        const val LINK_TIMEOUT_MS = 40_000L
        const val AUTH_WAIT_MS = 2_500L
        const val PAGE_ACK_TIMEOUT_MS = 3_000L
        const val IMAGE_SETTLE_MS = 1_000L
        const val IMAGE_ACK_TIMEOUT_MS = 2_500L
        const val IMAGE_FRAGMENT = 3_800
        const val TEXT_ACK_TIMEOUT_MS = 300L
        const val MIN_UPDATE_GAP_MS = 40L
        const val FIXED_RATE_GAP_MS = 62L
        const val INFO_INTERVAL_MS = 500L
        const val HEARTBEAT_MS = 5_000L
        const val MAX_RECONNECTS = 3
        const val LOG_LINES = 200
        private val TRANSIENT_GATT_ERRORS = listOf(" 133 ", "133 (", " 62 ", "62 (", "147 (", " 8 (", "Zeitüberschreitung")
    }
}
