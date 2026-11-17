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
    fun inspect(afterMs: Long = 0L): GameDeviceInspection {
        require(afterMs in 0L..7000L) { "invalid inspection delay" }
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
                    configureWatchdog(session)
                    // User may switch to the game before sampling; never inject input.
                    if (afterMs > 0) Thread.sleep(afterMs)
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

    /**
     * The first physical-screen step, scoped to ONE low-risk transition:
     * main screen -> phone menu. Nothing is inferred from raw model coordinates.
     * This method may be called only after a separate in-app confirmation.
     */
    fun openVerifiedMenuOnce(afterMs: Long = 5_000L): MenuStepOutcome {
        require(afterMs in 3_000L..7_000L) { "invalid device transition delay" }
        val app = context.applicationContext
        val tasks = TaskController.get(app)
        if (tasks.state.value.busy) return MenuStepOutcome("blocked", "M7A 正在执行任务")
        val captureBefore = LocalCaptureFrames.state.value
        if (!captureBefore.running || captureBefore.sessionId <= 0) {
            return MenuStepOutcome("blocked", "请先授权并开启本机屏幕观察")
        }
        val access = DeviceAccess(app)
        if (access.state(Privilege.SHIZUKU) != PermissionState.READY) {
            return MenuStepOutcome("blocked", "Shizuku 没有获得授权")
        }
        return try {
            val token = newToken()
            access.open(Privilege.SHIZUKU, token).use { lease ->
                Session(lease, token).use { session ->
                    configureWatchdog(session)
                    // Leave time to put the game in front, without touching the
                    // system UI. The session remains exclusive during this time.
                    Thread.sleep(afterMs)
                    var lastStatus = session.request(JSONObject().put("cmd", "status"))
                    for (attempt in 0..3) {
                        val proof = GameDeviceInspection.fromDeviceStatus(
                            lastStatus, SystemClock.elapsedRealtime())
                        if (proof.candidate) break
                        Thread.sleep(350)
                        lastStatus = session.request(JSONObject().put("cmd", "status"))
                    }
                    if (tasks.state.value.busy) {
                        return@use MenuStepOutcome("blocked", "M7A 任务取得了输入权")
                    }
                    var current = LocalCaptureFrames.state.value
                    var inspection = GameDeviceInspection.fromDeviceStatus(
                        lastStatus, SystemClock.elapsedRealtime())
                    var reason = MenuStepPolicy.check(inspection, current,
                        SystemClock.elapsedRealtime(), expectedSession = captureBefore.sessionId)
                    if (reason != null) return@use MenuStepOutcome("blocked", reason)
                    val original = current.preview
                        ?: return@use MenuStepOutcome("blocked", "当前没有可核验的游戏画面")
                    var localControl = SafeGameMenuDetector.findMainMenuControl(app, original)
                        ?: return@use MenuStepOutcome(
                            "blocked", "未在当前实际画面中独立定位主界面菜单图标")

                    // Re-read OS state immediately before dispatch. An old
                    // task-stack report or a newly changed frame invalidates
                    // the entire one-shot request.
                    lastStatus = session.request(JSONObject().put("cmd", "status"))
                    current = LocalCaptureFrames.state.value
                    inspection = GameDeviceInspection.fromDeviceStatus(
                        lastStatus, SystemClock.elapsedRealtime())
                    reason = MenuStepPolicy.check(inspection, current,
                        SystemClock.elapsedRealtime(), expectedSession = captureBefore.sessionId)
                    if (reason != null) return@use MenuStepOutcome("blocked", reason)
                    if (current.preview !== original) {
                        // Image changed between verification and dispatch:
                        // re-detect the icon on the newer frame, never reuse a
                        // stale/possibly shifted pixel coordinate.
                        localControl = SafeGameMenuDetector.findMainMenuControl(
                            app, current.preview ?: return@use MenuStepOutcome(
                                "blocked", "更新后的本机画面缺失"))
                            ?: return@use MenuStepOutcome("blocked", "画面变化后菜单图标不再匹配")
                    }

                    // Captured entire display must match the independently
                    // reported display-0 geometry. App-only sharing/cropping
                    // or rotation mismatches can NEVER authorize a touch.
                    val displays = lastStatus.optJSONArray("displays")
                    var width = 0
                    var height = 0
                    if (displays != null) {
                        for (i in 0 until displays.length()) {
                            val item = displays.optJSONObject(i) ?: continue
                            if (item.optInt("id", -1) == 0) {
                                width = item.optInt("width")
                                height = item.optInt("height")
                            }
                        }
                    }
                    if (width < 600 || height < 300 ||
                        current.width != width || current.height != height
                    ) return@use MenuStepOutcome("blocked", "屏幕共享尺寸与真实设备显示器不一致")

                    if (tasks.state.value.busy) return@use MenuStepOutcome("blocked", "其他脚本正在运行")
                    val request = JSONObject()
                        .put("cmd", "tap")
                        .put("display_id", 0)
                        .put("x", width.toDouble() * localControl.x /
                            (current.preview?.width ?: return@use MenuStepOutcome(
                                "blocked", "无可用目标截图")))
                        .put("y", height.toDouble() * localControl.y /
                            (current.preview?.height ?: return@use MenuStepOutcome(
                                "blocked", "无可用目标截图")))
                        .put("duration_ms", 50)
                    // DeviceService routes this through targetUid=the game.
                    // No force flag, arbitrary model target, or shell access.
                    val delivered = session.request(request).optBoolean("delivered", false)
                    if (!delivered) return@use MenuStepOutcome("blocked", "设备端没有确认触摸已投递")
                    val sourceFrame = current.frames
                    val sourceTime = current.lastFrameElapsedMs
                    val deadline = SystemClock.elapsedRealtime() + 3_500L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(250)
                        val after = LocalCaptureFrames.state.value
                        if (after.sessionId != captureBefore.sessionId || !after.running) break
                        if (after.frames <= sourceFrame || after.lastFrameElapsedMs <= sourceTime) continue
                        val fresh = SystemClock.elapsedRealtime() - after.lastFrameElapsedMs in 0L..2_000L
                        if (fresh && after.foregroundHint == ForegroundHint.GAME_CANDIDATE &&
                            SafeGameMenuDetector.isMenuVisible(app, after.preview ?: continue)) {
                            return@use MenuStepOutcome("verified", "已投递一次受保护点击，并在新画面匹配手机菜单")
                        }
                    }
                    // No success memory: output appeared or changed alone does
                    // NOT prove the requested in-game state happened.
                    MenuStepOutcome("unverified", "已投递一次点击，但新画面未确认手机菜单；已停止，不重试")
                }
            }
        } catch (_: Exception) {
            MenuStepOutcome("blocked", "设备连接、焦点限制或触摸被系统拒绝")
        }
    }

    private fun configureWatchdog(session: Session) {
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

/** One limited in-game menu action. No gameplay loop and no implicit retries. */
internal data class MenuStepOutcome(val stage: String, val explanation: String)

/** Pure device- and capture-state preflight; OS status is not model-provided. */
internal object MenuStepPolicy {
    fun check(proof: GameDeviceInspection, capture: LocalCaptureState,
              nowMs: Long, expectedSession: Long): String? {
        if (!proof.candidate) return "Shizuku 未确认星铁处于预期的可见任务"
        if (!capture.running || capture.sessionId != expectedSession || expectedSession <= 0L)
            return "本机屏幕共享会话已经结束或切换"
        if (capture.foregroundHint != ForegroundHint.GAME_CANDIDATE)
            return "未独立确认当前活动应用是星铁"
        if (capture.projectionVisible != true) return "屏幕共享区域不可见或状态未知"
        if (capture.frames < 1 || capture.preview == null)
            return "没有本机实时画面"
        if (capture.lastFrameElapsedMs <= 0 ||
            nowMs - capture.lastFrameElapsedMs !in 0L..1_500L)
            return "游戏截图已经过期"
        return null
    }
}
