package ch.madtreasures.g2direct.protocol

/** Decoded, protocol-level view of messages coming back from the glasses. */
sealed interface G2Event {
    /** Answer to CREATE (cmd 1), REBUILD (cmd 8), TEXT (cmd 6) or SHUTDOWN (cmd 10). */
    data class PageResult(val cmd: Int, val magic: Int?, val code: Int?) : G2Event

    /** Per-fragment image acknowledgement (ImgResCmd). */
    data class ImageAck(val containerId: Int?, val sessionId: Int?, val fragmentIndex: Int, val code: Int?) : G2Event

    data class HeartbeatAck(val code: Int?) : G2Event

    /** Touch / lifecycle events of our page (OS_NOTIFY_EVENT_TO_APP). */
    data class Input(val source: String, val type: Int, val containerId: Int?, val eventSource: Int?) : G2Event

    data class Auth(val ok: Boolean) : G2Event

    data class DeviceInfo(
        val batteryPercent: Int?,
        val charging: Boolean?,
        val leftFirmware: String?,
        val rightFirmware: String?,
    ) : G2Event

    data class Other(val serviceId: Int, val summary: String) : G2Event
}

object G2Responses {

    fun decode(message: G2Inbound): G2Event {
        val pb = ProtoMessage.parse(message.payload)
        return when (message.serviceId) {
            ServiceId.EVEN_HUB -> decodeEvenHub(pb)
            ServiceId.DEVICE_SETTINGS -> decodeDeviceSettings(pb)
            ServiceId.G2_SETTING -> decodeG2Setting(pb)
            else -> G2Event.Other(message.serviceId, pb.toString().take(120))
        }
    }

    private fun decodeEvenHub(pb: ProtoMessage): G2Event {
        val cmd = pb.int(1) ?: return G2Event.Other(ServiceId.EVEN_HUB, "ohne Cmd: $pb")
        val magic = pb.int(2)
        // ImgResCmd lives in field 6 and is recognised by its content rather than by cmd,
        // because the firmware has been seen answering with differing cmd values.
        pb.message(6)?.let { img ->
            if (img.has(8) || img.has(3)) {
                return G2Event.ImageAck(img.int(1), img.int(3), img.int(6) ?: 0, img.int(8))
            }
        }
        return when (cmd) {
            EvenHubCmd.NOTIFY_EVENT_TO_APP -> decodeDeviceEvent(pb.message(13))
            EvenHubCmd.RESPONSE_CREATE_STARTUP_PAGE, EvenHubCmd.CREATE_STARTUP_PAGE ->
                G2Event.PageResult(EvenHubCmd.RESPONSE_CREATE_STARTUP_PAGE, magic, pb.message(4)?.int(1) ?: 0)
            EvenHubCmd.RESPONSE_REBUILD_PAGE, EvenHubCmd.REBUILD_PAGE ->
                G2Event.PageResult(EvenHubCmd.RESPONSE_REBUILD_PAGE, magic, pb.message(8)?.int(1))
            EvenHubCmd.RESPONSE_TEXT_DATA, EvenHubCmd.UPDATE_TEXT_DATA ->
                G2Event.PageResult(EvenHubCmd.RESPONSE_TEXT_DATA, magic, pb.message(10)?.int(1))
            EvenHubCmd.RESPONSE_SHUTDOWN_PAGE, EvenHubCmd.SHUTDOWN_PAGE ->
                G2Event.PageResult(EvenHubCmd.RESPONSE_SHUTDOWN_PAGE, magic, pb.message(12)?.int(1))
            EvenHubCmd.HEARTBEAT, EvenHubCmd.RESPONSE_HEARTBEAT ->
                G2Event.HeartbeatAck(pb.message(15)?.int(2))
            else -> G2Event.Other(ServiceId.EVEN_HUB, "cmd=$cmd $pb".take(120))
        }
    }

    /** SendDeviceEvent { 1: List_ItemEvent, 2: Text_ItemEvent, 3: Sys_ItemEvent }. */
    private fun decodeDeviceEvent(ev: ProtoMessage?): G2Event {
        if (ev == null) return G2Event.Other(ServiceId.EVEN_HUB, "Ereignis ohne Inhalt")
        ev.message(3)?.let { sys ->
            // proto3 omits CLICK (0), so a missing type means "click".
            return G2Event.Input("sys", sys.int(1) ?: OsEvent.CLICK, null, sys.int(2))
        }
        ev.message(2)?.let { text ->
            return G2Event.Input("text", text.int(3) ?: OsEvent.CLICK, text.int(1), null)
        }
        ev.message(1)?.let { list ->
            return G2Event.Input("list", list.int(5) ?: OsEvent.CLICK, list.int(1), null)
        }
        return G2Event.Other(ServiceId.EVEN_HUB, "Ereignis $ev")
    }

    private fun decodeDeviceSettings(pb: ProtoMessage): G2Event {
        return when (pb.int(1)) {
            G2Messages.DEV_CFG_AUTH_ID -> G2Event.Auth((pb.message(3)?.int(1) ?: 0) != 0)
            G2Messages.DEV_CFG_HEARTBEAT_ID -> G2Event.HeartbeatAck(null)
            else -> G2Event.Other(ServiceId.DEVICE_SETTINGS, pb.toString().take(120))
        }
    }

    /** G2SettingPackage answer: field 4 = DeviceReceiveRequest response (battery, firmware). */
    private fun decodeG2Setting(pb: ProtoMessage): G2Event {
        val info = pb.message(4) ?: return G2Event.Other(ServiceId.G2_SETTING, pb.toString().take(120))
        val battery = info.int(12)?.takeIf { it in 0..100 }
        return G2Event.DeviceInfo(
            batteryPercent = battery,
            charging = info.int(13)?.let { it != 0 },
            leftFirmware = info.string(5)?.trim()?.takeIf { it.isNotEmpty() },
            rightFirmware = info.string(6)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
