package top.m7a.ui

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.march7th.deviceservice.protocol.Message
import org.march7th.deviceservice.protocol.MessageCodec
import org.march7th.deviceservice.protocol.Protocol
import top.m7a.DeviceAccess
import top.m7a.DeviceLease
import top.m7a.PermissionState
import top.m7a.Privilege
import top.m7a.TaskController
import top.m7a.newToken
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A real, isolated Shizuku connection to the EXISTING M7A device service.
 *
 * No M7A Python task is launched, no display is created, no game is launched,
 * and this reader never sends taps, keys, shell commands or screen contents.
 * DeviceAccess owns a single-instance service lease; concurrent M7A tasks
 * fail closed. This diagnostic establishes what the OS actually knows about
 * the game before any future trusted GameExecutor is allowed to exist.
 */
internal class TrustedGameDevice(private val context: Context) {
    fun inspect(): GameDeviceInspection {
        val app = context.applicationContext
        val tasks = TaskController.get(app)
        if (tasks.state.value.busy) return GameDeviceInspection.denied("M7A 任务正占用输入")
        val access = DeviceAccess(app)
        if (access.state(Privilege.SHIZUKU) != PermissionState.READY) {
            return GameDeviceInspection.denied("Shizuku 尚未授权或没有运行")
        }
        return try {
            val token = newToken()
            access.open(Privilege.SHIZUKU, token).use { lease ->
                if (tasks.state.value.busy) return@use GameDeviceInspection.denied("M7A 输入权发生变化")
                Session(lease, token).use { session ->
                    // Strictly observational watchdog: no auto-focus, launch,
                    // move-back, relaunch, or power/sound side effects.
                    session.request(JSONObject()
                        .put("cmd", "watchdog")
                        .put("package", GAME_PACKAGE)
                        .put("main_activity", MAIN_ACTIVITY)
                        .put("display_id", 0)
                        .put("auto_move_back", false)
                        .put("auto_focus", false)
                        .put("relaunch", false)
                        .put("stall_ms", 0)
                        .put("subscribe", false))
                    // Let the OS task-stack observer collect one fresh sample.
                    var status: JSONObject? = null
                    for (attempt in 0..3) {
                        if (tasks.state.value.busy) break
                        if (attempt > 0) Thread.sleep(350)
                        val candidate = session.request(JSONObject().put("cmd", "status"))
                        status = candidate
                        val checkedMs = candidate.optJSONObject("watchdog")?.optLong("task_checked_ms") ?: 0L
                        if (checkedMs > 0) break
                    }
                    val checked = status ?: return@use GameDeviceInspection.denied("任务状态不可读取")
                    GameDeviceInspection.fromDeviceStatus(checked, SystemClock.elapsedRealtime())
                }
            }
        } catch (_: Exception) {
            // Never expose a Shizuku bearer token or raw device stderr to UI/logs.
            GameDeviceInspection.denied("Shizuku 设备探测失败或服务已被其他任务占用")
        }
    }

    private class Session(lease: DeviceLease, token: String) : Closeable {
        private val socket = Socket()
        private val input: java.io.InputStream
        private val output: java.io.OutputStream

        init {
            socket.connect(InetSocketAddress("127.0.0.1", lease.port), 2_000)
            socket.soTimeout = 4_000
            socket.tcpNoDelay = true
            input = socket.getInputStream().buffered()
            output = socket.getOutputStream()
            MessageCodec.write(output, Message(JSONObject()
                .put("cmd", "hello").put("protocol", Protocol.VERSION).put("token", token)))
            val hello = MessageCodec.read(input, Protocol.MAX_HANDSHAKE_HEADER_BYTES, 0)
                ?: throw IOException("device handshake closed")
            if (hello.header.optBoolean("ok") != true ||
                hello.header.optInt("protocol", -1) != Protocol.VERSION) {
                throw IOException("device handshake rejected")
            }
        }

        fun request(header: JSONObject): JSONObject {
            // Request IDs are local; no model-originated JSON reaches this port.
            MessageCodec.write(output, Message(header))
            val reply = MessageCodec.read(input, Protocol.MAX_HANDSHAKE_HEADER_BYTES, 0)
                ?: throw IOException("device disconnected")
            if (reply.header.optBoolean("ok") != true) throw IOException("device request denied")
            return reply.header
        }

        override fun close() = socket.close()
    }

    companion object {
        const val GAME_PACKAGE = "com.miHoYo.hkrpg"
        const val MAIN_ACTIVITY = "com.mihoyo.combosdk.ComboSDKActivity"
    }
}

/**
 * OS-reported candidate, NOT verified foreground focus and NEVER a touch permit.
 * This prevents treating Usage Access (or a model's claim) as trusted game focus.
 */
internal data class GameDeviceInspection(
    val status: String,
    val hasGameTask: Boolean,
    val taskVisible: Boolean,
    val taskOnDefaultDisplay: Boolean,
    val mainActivityOnTop: Boolean,
    val taskObservationFresh: Boolean,
    val screenInteractive: Boolean,
    val screenUnlocked: Boolean,
) {
    val candidate: Boolean
        get() = hasGameTask && taskVisible && taskOnDefaultDisplay && mainActivityOnTop &&
            taskObservationFresh && screenInteractive && screenUnlocked

    companion object {
        fun denied(message: String) = GameDeviceInspection(
            message, false, false, false, false, false, false, false)

        fun fromDeviceStatus(raw: JSONObject, nowElapsedMs: Long): GameDeviceInspection {
            val w = raw.optJSONObject("watchdog")
            val task = w?.optJSONObject("task")
            val power = raw.optJSONObject("power")
            val checked = w?.optLong("task_checked_ms") ?: 0L
            val main = w?.optString("main_activity").orEmpty()
            val packageOk = w?.optString("package") == TrustedGameDevice.GAME_PACKAGE
            val correctTarget = w?.optInt("target_display_id", -1) == 0
            val expectedComponent = TrustedGameDevice.GAME_PACKAGE + "/" + TrustedGameDevice.MAIN_ACTIVITY
            val top = task?.optString("top_activity").orEmpty()
            val valid = w?.optBoolean("enabled") == true && packageOk && correctTarget &&
                main == TrustedGameDevice.MAIN_ACTIVITY
            val gameTask = valid && task != null
            val visible = task?.optBoolean("visible") == true
            val defaultDisplay = task?.optInt("display_id", -1) == 0
            val fresh = checked > 0 && nowElapsedMs >= checked &&
                nowElapsedMs - checked <= 1_800L
            val interactive = power?.optBoolean("interactive") == true &&
                w?.optBoolean("interactive") == true
            val unlocked = power?.optBoolean("keyguard_locked", true) == false &&
                w?.optBoolean("keyguard_locked", true) == false
            val result = GameDeviceInspection(
                status = if (!valid) "设备报告缺少星铁看门狗"
                    else if (!gameTask) "设备未观察到星铁任务"
                    else if (!fresh) "设备任务状态已过期"
                    else if (!visible || !defaultDisplay || top != expectedComponent)
                        "星铁不在可见的预期游戏主界面"
                    else if (!interactive || !unlocked) "屏幕未解锁或不可交互"
                    else "星铁任务候选已找到（仍需真实窗口焦点核验）",
                hasGameTask = gameTask,
                taskVisible = visible,
                taskOnDefaultDisplay = defaultDisplay,
                mainActivityOnTop = top == expectedComponent,
                taskObservationFresh = fresh,
                screenInteractive = interactive,
                screenUnlocked = unlocked,
            )
            return result
        }
    }
}
