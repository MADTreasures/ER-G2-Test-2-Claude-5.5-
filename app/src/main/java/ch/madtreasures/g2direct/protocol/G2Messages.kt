package ch.madtreasures.g2direct.protocol

/** Service ids (byte 6 of every frame), from Even's service_id_def.proto as used by MentraOS. */
object ServiceId {
    const val DASHBOARD = 0x01
    const val MENU = 0x03
    const val EVEN_AI = 0x07
    const val G2_SETTING = 0x09
    const val UI_SETTING = 0x0C
    const val GESTURE_CTRL = 0x0D
    const val ONBOARDING = 0x10
    const val DEVICE_SETTINGS = 0x80
    const val EVEN_HUB_CTRL = 0x81
    const val EVEN_HUB = 0xE0

    fun name(sid: Int): String = when (sid) {
        DASHBOARD -> "Dashboard"
        MENU -> "Menu"
        EVEN_AI -> "EvenAI"
        G2_SETTING -> "G2Setting"
        UI_SETTING -> "UiSetting"
        GESTURE_CTRL -> "GestureCtrl"
        ONBOARDING -> "Onboarding"
        DEVICE_SETTINGS -> "DevSettings"
        EVEN_HUB_CTRL -> "EvenHubCtrl"
        EVEN_HUB -> "EvenHub"
        else -> "0x%02X".format(sid)
    }
}

/** EvenHub_Cmd_List (EvenHub.proto). Even values are requests, odd ones the firmware's answers. */
object EvenHubCmd {
    const val CREATE_STARTUP_PAGE = 0
    const val RESPONSE_CREATE_STARTUP_PAGE = 1
    const val NOTIFY_EVENT_TO_APP = 2
    const val UPDATE_IMAGE_RAW_DATA = 3
    const val RESPONSE_IMAGE_RAW_DATA = 4
    const val UPDATE_TEXT_DATA = 5
    const val RESPONSE_TEXT_DATA = 6
    const val REBUILD_PAGE = 7
    const val RESPONSE_REBUILD_PAGE = 8
    const val SHUTDOWN_PAGE = 9
    const val RESPONSE_SHUTDOWN_PAGE = 10
    const val HEARTBEAT = 12
    const val RESPONSE_HEARTBEAT = 13
    const val NOTIFY_MENU_STARTUP = 17
}

/** EvenHub_ErrorCode_List (EvenHub.proto). */
object EvenHubResult {
    const val CREATE_SUCCESS = 0
    const val CREATE_INVALID_CONTAINER = 1
    const val CREATE_OVERSIZE = 2
    const val CREATE_OUT_OF_MEMORY = 3
    const val IMAGE_SUCCESS = 4
    const val IMAGE_FAILED = 5
    const val REBUILD_SUCCESS = 6
    const val REBUILD_FAILED = 7
    const val TEXT_SUCCESS = 8
    const val TEXT_FAILED = 9
    const val SHUTDOWN_SUCCESS = 10
    const val SHUTDOWN_FAILED = 11
    const val HEARTBEAT_SUCCESS = 12

    fun describe(code: Int): String = when (code) {
        CREATE_SUCCESS -> "Seite erstellt"
        CREATE_INVALID_CONTAINER -> "ungültiger Container"
        CREATE_OVERSIZE -> "Container zu groß"
        CREATE_OUT_OF_MEMORY -> "kein Speicher auf der Brille"
        IMAGE_SUCCESS -> "Bild ok"
        IMAGE_FAILED -> "Bild fehlgeschlagen"
        REBUILD_SUCCESS -> "Seite neu aufgebaut"
        REBUILD_FAILED -> "Neuaufbau fehlgeschlagen"
        TEXT_SUCCESS -> "Text ok"
        TEXT_FAILED -> "Text fehlgeschlagen"
        SHUTDOWN_SUCCESS -> "Seite geschlossen"
        SHUTDOWN_FAILED -> "Schließen fehlgeschlagen"
        HEARTBEAT_SUCCESS -> "Heartbeat ok"
        else -> "Code $code"
    }
}

/** OsEventTypeList (EvenHub.proto). */
object OsEvent {
    const val CLICK = 0
    const val SCROLL_TOP = 1
    const val SCROLL_BOTTOM = 2
    const val DOUBLE_CLICK = 3
    const val FOREGROUND_ENTER = 4
    const val FOREGROUND_EXIT = 5
    const val ABNORMAL_EXIT = 6
    const val SYSTEM_EXIT = 7
    const val IMU_DATA_REPORT = 8

    fun describe(type: Int): String = when (type) {
        CLICK -> "Tippen"
        SCROLL_TOP -> "Wischen (oben)"
        SCROLL_BOTTOM -> "Wischen (unten)"
        DOUBLE_CLICK -> "Doppeltippen"
        FOREGROUND_ENTER -> "Vordergrund"
        FOREGROUND_EXIT -> "Hintergrund"
        ABNORMAL_EXIT -> "abnormal beendet"
        SYSTEM_EXIT -> "vom System beendet"
        IMU_DATA_REPORT -> "IMU"
        else -> "Ereignis $type"
    }
}

data class TextContainer(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val content: String = "",
    val borderWidth: Int = 0,
    val borderColor: Int = 0,
    val borderRadius: Int = 0,
    val padding: Int = 0,
    val eventCapture: Boolean = false,
) {
    init {
        require(name.length <= 14) { "Containername '$name' zu lang (max 14)" }
    }
}

data class ImageContainer(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    init {
        require(name.length <= 14) { "Containername '$name' zu lang (max 14)" }
        require(width in 20..288 && height in 20..144) { "Bildcontainer ${width}x$height außerhalb 20..288 x 20..144" }
    }
}

/**
 * Protobuf payload builders. Field numbers follow EvenHub.proto /
 * dev_config_protocol.proto as recovered from the Even app (published as generated
 * code in g2-kit-unofficial) and as sent by MentraOS G2.kt in production.
 */
object G2Messages {

    // ---- dev_config_protocol.proto (service 0x80) ----

    private const val DEV_CFG_AUTHENTICATION = 4
    private const val DEV_CFG_PIPE_ROLE_CHANGE = 5
    private const val DEV_CFG_BASE_CONN_HEART_BEAT = 14
    private const val DEV_CFG_TIME_SYNC = 128
    const val DEV_CFG_AUTH_ID = DEV_CFG_AUTHENTICATION
    const val DEV_CFG_HEARTBEAT_ID = DEV_CFG_BASE_CONN_HEART_BEAT

    /** AuthMgr { secAuth = true, phoneType = PHONE_ANDROID (4) }. */
    fun auth(magic: Int): ByteArray = ProtoWriter.build {
        int(1, DEV_CFG_AUTHENTICATION)
        int(2, magic)
        message(3) {
            bool(1, true)
            int(2, 4)
        }
    }

    /** PipeRoleChange { asCmdRole = RIGHT (1) } - makes the right arm the command pipe. */
    fun pipeRoleChange(magic: Int): ByteArray = ProtoWriter.build {
        int(1, DEV_CFG_PIPE_ROLE_CHANGE)
        int(2, magic)
        message(4) { int(1, 1) }
    }

    /**
     * TimeSync. The firmware ignores the timezone field, so - like MentraOS - the local
     * offset is folded into the timestamp itself.
     */
    fun timeSync(magic: Int, epochMillis: Long, utcOffsetMillis: Int): ByteArray = ProtoWriter.build {
        int(1, DEV_CFG_TIME_SYNC)
        int(2, magic)
        message(128) { int(1, ((epochMillis + utcOffsetMillis) / 1000L).toInt()) }
    }

    fun deviceHeartbeat(magic: Int): ByteArray = ProtoWriter.build {
        int(1, DEV_CFG_BASE_CONN_HEART_BEAT)
        int(2, magic)
        message(13, ByteArray(0))
    }

    // ---- other services used in the Even-compatible start-up sequence ----

    /** onboarding.proto CONFIG { processId = FINISH }: otherwise the firmware keeps its own onboarding UI. */
    fun onboardingFinished(magic: Int): ByteArray = ProtoWriter.build {
        int(1, 1)
        int(2, magic)
        message(3) { int(1, 4) }
    }

    /** gesture_ctrl lifecycle registration (sent by the Even app and MentraOS after auth). */
    fun gestureControlInit(magic: Int): ByteArray = ProtoWriter.build {
        int(1, 0)
        int(2, magic)
    }

    /** g2_setting.proto DEVICE_RECEIVE_REQUEST { settingInfoType = APP_REQUIRE_BASIC_SETTING } - read-only. */
    fun requestDeviceInfo(magic: Int): ByteArray = ProtoWriter.build {
        int(1, 2)
        int(2, magic)
        message(4) { int(1, 1) }
    }

    // ---- EvenHub.proto (service 0xE0) ----

    private fun evenHub(cmd: Int, magic: Int, field: Int, body: ByteArray): ByteArray = ProtoWriter.build {
        int(1, cmd)
        int(2, magic)
        message(field, body)
    }

    fun textContainerProperty(c: TextContainer): ByteArray = ProtoWriter.build {
        int(1, c.x)
        int(2, c.y)
        int(3, c.width)
        int(4, c.height)
        int(5, c.borderWidth)
        int(6, c.borderColor)
        int(7, c.borderRadius)
        int(8, c.padding)
        int(9, c.id)
        string(10, c.name)
        bool(11, c.eventCapture)
        string(12, c.content)
    }

    fun imageContainerProperty(c: ImageContainer): ByteArray = ProtoWriter.build {
        int(1, c.x)
        int(2, c.y)
        int(3, c.width)
        int(4, c.height)
        int(5, c.id)
        string(6, c.name)
    }

    private fun pageBody(texts: List<TextContainer>, images: List<ImageContainer>): ByteArray {
        require(texts.size <= 8) { "max. 8 Text-/Listencontainer" }
        require(images.size <= 4) { "max. 4 Bildcontainer" }
        require(texts.count { it.eventCapture } == 1) { "genau ein Container braucht isEventCapture" }
        val ids = texts.map { it.id } + images.map { it.id }
        require(ids.toSet().size == ids.size) { "Container-IDs doppelt" }
        return ProtoWriter.build {
            int(1, texts.size + images.size)
            for (t in texts) message(3, textContainerProperty(t))
            for (i in images) message(4, imageContainerProperty(i))
        }
    }

    fun createPage(magic: Int, texts: List<TextContainer>, images: List<ImageContainer>): ByteArray =
        evenHub(EvenHubCmd.CREATE_STARTUP_PAGE, magic, 3, pageBody(texts, images))

    fun rebuildPage(magic: Int, texts: List<TextContainer>, images: List<ImageContainer>): ByteArray =
        evenHub(EvenHubCmd.REBUILD_PAGE, magic, 7, pageBody(texts, images))

    /**
     * TextContainerUpgrade. The firmware *replaces* [replaceLength] bytes starting at
     * [offset] with [content] (MentraOS observed that a shorter length leaves the rest of the
     * old text in place), so callers pass the old content length for a full replacement.
     */
    fun updateText(
        magic: Int,
        containerId: Int,
        containerName: String,
        content: String,
        replaceLength: Int,
        offset: Int = 0,
    ): ByteArray = evenHub(
        EvenHubCmd.UPDATE_TEXT_DATA, magic, 9,
        ProtoWriter.build {
            int(1, containerId)
            string(2, containerName)
            int(3, offset)
            int(4, replaceLength)
            string(5, content)
        }
    )

    fun imageFragment(
        magic: Int,
        containerId: Int,
        containerName: String,
        sessionId: Int,
        totalSize: Int,
        fragmentIndex: Int,
        fragment: ByteArray,
    ): ByteArray = evenHub(
        EvenHubCmd.UPDATE_IMAGE_RAW_DATA, magic, 5,
        ProtoWriter.build {
            int(1, containerId)
            string(2, containerName)
            int(3, sessionId)
            int(4, totalSize)
            int(5, 0) // compressMode: none
            int(6, fragmentIndex)
            int(7, fragment.size)
            bytes(8, fragment)
        }
    )

    /** ShutDownContaniner { exitMode }: 0 = leave immediately. */
    fun shutdownPage(magic: Int, exitMode: Int = 0): ByteArray =
        evenHub(EvenHubCmd.SHUTDOWN_PAGE, magic, 11, ProtoWriter.build { int(1, exitMode) })

    fun evenHubHeartbeat(magic: Int): ByteArray = evenHub(EvenHubCmd.HEARTBEAT, magic, 14, ByteArray(0))
}
