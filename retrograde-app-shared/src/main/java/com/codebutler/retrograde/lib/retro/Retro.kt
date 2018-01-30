/*
 * Retro.kt
 *
 * Copyright (C) 2017 Retrograde Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.retrograde.lib.retro

import android.graphics.PixelFormat
import android.os.Build
import com.codebutler.retrograde.common.BufferCache
import com.codebutler.retrograde.common.jna.NativeString
import com.codebutler.retrograde.common.jna.SizeT
import com.codebutler.retrograde.common.jna.UnsignedInt
import com.codebutler.retrograde.lib.binding.LibC
import com.codebutler.retrograde.lib.binding.LibRetrograde
import com.codebutler.retrograde.lib.retro.LibRetro.retro_system_info
import com.codebutler.retrograde.lib.retro.LibRetro.retro_variable
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.ByteBuffer

/**
 * Java idomatic wrapper around [LibRetro].
 */
class Retro(coreLibraryName: String) {

    private val libRetro: LibRetro = Native.loadLibrary(
            coreLibraryName,
            LibRetro::class.java,
            mapOf(Library.OPTION_STRUCTURE_ALIGNMENT to
                    // Using ALIGN_DEFAULT on x86 caused issues with float field alignment (retro_system_timing).
                    if ("x86" in Build.SUPPORTED_32_BIT_ABIS && Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
                        Structure.ALIGN_NONE
                    } else {
                        Structure.ALIGN_DEFAULT
                    }
            ))

    var environmentCallback: EnvironmentCallback? = null
    var videoCallback: ((data: ByteArray, width: Int, height: Int, pitch: Int) -> Unit)? = null
    var audioSampleCallback: ((left: Short, right: Short) -> Unit)? = null
    var audioSampleBatchCallback: ((data: ByteArray) -> Long)? = null
    var inputPollCallback: (() -> Unit)? = null
    var inputStateCallback: ((port: Int, device: Int, index: Int, id: Int) -> Boolean)? = null

    private val environment = RetroEnvironmentT(this)

    private val videoRefresh = object : LibRetro.retro_video_refresh_t {
        override fun invoke(data: Pointer, width: UnsignedInt, height: UnsignedInt, pitch: SizeT) {
            val buffer = videoBufferCache.getBuffer(height.toInt() * pitch.toInt())
            data.read(0, buffer, 0, buffer.size)
            videoCallback?.invoke(buffer, width.toInt(), height.toInt(), pitch.toInt())
        }
    }

    private val audioSample = object : LibRetro.retro_audio_sample_t {
        override fun apply(left: Short, right: Short) {
            audioSampleCallback?.invoke(left, right)
        }
    }

    private val audioSampleBatch = object : LibRetro.retro_audio_sample_batch_t {
        override fun apply(data: Pointer, frames: SizeT): SizeT {
            // Each frame is 4 bytes (16-bit stereo)
            val buffer = audioBufferCache.getBuffer(frames.toInt() * 4)
            data.read(0, buffer, 0, buffer.size)
            audioSampleBatchCallback?.invoke(buffer)
            return frames
        }
    }

    private val inputPoll = object : LibRetro.retro_input_poll_t {
        override fun apply() {
            inputPollCallback?.invoke()
        }
    }

    private val inputState = object : LibRetro.retro_input_state_t {
        override fun apply(port: UnsignedInt, device: UnsignedInt, index: UnsignedInt, id: UnsignedInt): Short {
            val isPressed = inputStateCallback?.invoke(port.toInt(), device.toInt(), index.toInt(), id.toInt()) == true
            return if (isPressed) 1 else 0
        }
    }

    private val videoBufferCache = BufferCache()
    private val audioBufferCache = BufferCache()

    data class ControllerDescription(
            val desc: String,
            val id: Device)

    data class ControllerInfo(
            val types: List<ControllerDescription>)

    data class SystemInfo(
            val libraryName: String,
            val libraryVersion: String,
            val validExtensions: String?,
            val needFullpath: Boolean,
            val blockExtract: Boolean)

    data class GameGeometry(
            val baseWidth: Int,
            val baseHeight: Int,
            val maxWidth: Int,
            var maxHeight: Int,
            var aspectRatio: Float) {

        companion object {
            fun create(geometry: LibRetro.retro_game_geometry) = GameGeometry(
                    geometry.base_width!!.toInt(),
                    geometry.base_height!!.toInt(),
                    geometry.max_width!!.toInt(),
                    geometry.max_height!!.toInt(),
                    geometry.aspect_ratio!!)
        }
    }

    data class SystemTiming(
            val fps: Double,
            val sample_rate: Double)

    data class SystemAVInfo(
            val geometry: GameGeometry,
            val timing: SystemTiming) {

        companion object {
            fun create(info: LibRetro.retro_system_av_info): SystemAVInfo = SystemAVInfo(
                    GameGeometry.create(info.geometry!!),
                    SystemTiming(
                            info.timing!!.fps!!,
                            info.timing!!.sample_rate!!
                    )
            )
        }
    }

    data class InputDescriptor(
            val port: Int,
            val device: Device,
            val index: Int,
            val id: DeviceId,
            val description: String)

    data class Variable(val description: String, val choices: List<String>, var value: String? = null)

    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    enum class Device(val value: Int) {
        NONE(0),
        JOYPAD(1),
        MOUSE(2),
        KEYBOARD(3),
        LIGHTGUN(4),
        ANALOG(5),
        POINTER(6);

        companion object {
            private val valueCache = mapOf(*Device.values().map { it.value to it }.toTypedArray())
            fun fromValue(value: Int) = valueCache[value]
        }
    }

    enum class DeviceId(val value: Int) {
        JOYPAD_B(0),
        JOYPAD_Y(1),
        JOYPAD_SELECT(2),
        JOYPAD_START(3),
        JOYPAD_UP(4),
        JOYPAD_DOWN(5),
        JOYPAD_LEFT(6),
        JOYPAD_RIGHT(7),
        JOYPAD_A(8),
        JOYPAD_X(9),
        JOYPAD_L(10),
        JOYPAD_R(11),
        JOYPAD_L2(12),
        JOYPAD_R2(13),
        JOYPAD_L3(14),
        JOYPAD_R3(15);

        companion object {
            private val valueCache = mapOf(*DeviceId.values().map { it.value to it }.toTypedArray())
            fun fromValue(value: Int) = valueCache[value]
        }
    }

    enum class Region(val value: Int) {
        REGION_NTSC(0),
        REGION_PAL(1);

        companion object {
            private val valueCache = mapOf(*Region.values().map { it.value to it }.toTypedArray())
            fun fromValue(value: Int) = valueCache[value]!!
        }
    }

    enum class MemoryId(val value: Int) {
        SAVE_RAM(0),
        MEMORY_RTC(1),
        SYSTEM_RAM(2),
        VIDEO_RAM(3);

        companion object {
            private val valueCache = mapOf(*MemoryId.values().map { it.value to it }.toTypedArray())
            fun fromValue(value: Int) = valueCache[value]!!
        }
    }

    @Suppress("EnumEntryName")
    enum class PixelFormat(val value: Int) {
        `0RGB1555`(0),
        XRGB8888(1),
        RGB565(2);

        companion object {
            private val valueCache = mapOf(*PixelFormat.values().map { it.value to it }.toTypedArray())
            fun fromValue(value: Int) = valueCache[value]!!
        }

        val info: android.graphics.PixelFormat
            get() {
                val format = when (this) {
                    XRGB8888 -> android.graphics.PixelFormat.RGBA_8888
                    RGB565 -> android.graphics.PixelFormat.RGB_565
                    else -> TODO()
                }
                val pixelFormatInfo = PixelFormat()
                android.graphics.PixelFormat.getPixelFormatInfo(format, pixelFormatInfo)
                return pixelFormatInfo
            }
    }

    interface EnvironmentCallback {

        fun onSetVariables(variables: Map<String, Variable>)

        fun onSetSupportAchievements(supportsAchievements: Boolean)

        fun onSetPerformanceLevel(performanceLevel: Int)

        fun onGetVariable(name: String): String?

        fun onSetPixelFormat(pixelFormat: PixelFormat): Boolean

        fun onSetInputDescriptors(descriptors: List<InputDescriptor>)

        fun onSetControllerInfo(info: List<ControllerInfo>)

        fun onGetVariableUpdate(): Boolean

        fun onSetMemoryMaps()

        fun onGetLogInterface(): LogInterface?

        fun onSetSystemAvInfo(info: SystemAVInfo)

        fun onGetSystemDirectory(): String?

        fun onSetGeometry(geometry: GameGeometry)

        fun onGetSaveDirectory(): String?

        fun onUnsupportedCommand(cmd: Int)

        fun onUnhandledException(error: Throwable)
    }

    interface LogInterface {

        fun onLogMessage(level: LogLevel, message: String)
    }

    fun getSystemInfo(): SystemInfo {
        val info = retro_system_info()
        libRetro.retro_get_system_info(info)
        return SystemInfo(
                libraryName = info.library_name!!,
                libraryVersion = info.library_version!!,
                validExtensions = info.valid_extensions,
                needFullpath = info.need_fullpath,
                blockExtract = info.block_extract
        )
    }

    fun getSystemAVInfo(): SystemAVInfo {
        val info = LibRetro.retro_system_av_info()
        libRetro.retro_get_system_av_info(info)
        return SystemAVInfo.create(info)
    }

    fun getRegion(): Region {
        val region = libRetro.retro_get_region()
        return Region.fromValue(region.toInt())
    }

    fun init() {
        libRetro.retro_set_video_refresh(videoRefresh)
        libRetro.retro_set_environment(environment)
        libRetro.retro_set_audio_sample(audioSample)
        libRetro.retro_set_audio_sample_batch(audioSampleBatch)
        libRetro.retro_set_input_poll(inputPoll)
        libRetro.retro_set_input_state(inputState)
        libRetro.retro_init()
    }

    fun deinit() {
        libRetro.retro_deinit()
    }

    fun loadGame(filePath: String): Boolean {
        val info = LibRetro.retro_game_info()
        info.path = filePath
        info.write()
        return libRetro.retro_load_game(info)
    }

    fun loadGame(data: ByteArray): Boolean {
        val memory = Memory(data.size.toLong())
        memory.write(0, data, 0, data.size)

        val info = LibRetro.retro_game_info()
        info.data = memory
        info.size = SizeT(data.size.toLong())
        info.write()

        return libRetro.retro_load_game(info)
    }

    fun unloadGame() {
        libRetro.retro_unload_game()
    }

    fun run() {
        libRetro.retro_run()
    }

    fun getMemoryData(memory: MemoryId): ByteArray? {
        val id = UnsignedInt(memory.value.toLong())
        val size = libRetro.retro_get_memory_size(id).toInt()
        val pointer = libRetro.retro_get_memory_data(id)
        return pointer?.getByteArray(0, size)
    }

    fun setMemoryData(memory: MemoryId, data: ByteArray) {
        val id = UnsignedInt(memory.value.toLong())
        val pointer = libRetro.retro_get_memory_data(id)
        pointer?.write(0, data, 0, data.size)
    }

    private class RetroEnvironmentT(private val retro: Retro) : LibRetro.retro_environment_t {
        private var logPrintfCb: LibRetro.retro_log_printf_t? = null

        override fun invoke(cmd: UnsignedInt, data: Pointer): Boolean {
            val callback = retro.environmentCallback ?: return false
            try {
                when (cmd.toInt()) {
                    LibRetro.RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL -> {
                        callback.onSetPerformanceLevel(data.getInt(0))
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY -> {
                        val directory = callback.onGetSystemDirectory()
                        if (directory != null) {
                            val ref = PointerByReference(data)
                            ref.value.setPointer(0, NativeString(directory).pointer)
                        }
                        return directory != null
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_PIXEL_FORMAT -> {
                        val pixelFormat = data.getInt(0)
                        return callback.onSetPixelFormat(PixelFormat.fromValue(pixelFormat))
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS -> {
                        val descriptors = mutableListOf<InputDescriptor>()
                        var offset = 0L
                        while (true) {
                            val descriptor = LibRetro.retro_input_descriptor(data.share(offset))
                            descriptor.description ?: break
                            val device = Device.fromValue(descriptor.device!!.toInt()) ?: continue
                            descriptors.add(InputDescriptor(
                                    descriptor.port!!.toInt(),
                                    device,
                                    descriptor.index!!.toInt(),
                                    DeviceId.fromValue(descriptor.id!!.toInt())!!,
                                    descriptor.description!!))

                            offset += descriptor.size()
                        }
                        callback.onSetInputDescriptors(descriptors.toList())
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_VARIABLE -> {
                        val variable = retro_variable(data)
                        variable.value = callback.onGetVariable(variable.key!!)
                        variable.write()
                        return variable.value != null
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_VARIABLES -> {
                        val variables = mutableMapOf<String, Variable>()
                        var offset = 0L
                        while (true) {
                            val v = retro_variable(data.share(offset))
                            val name = v.key ?: break
                            val value = v.value ?: break
                            val (description, choicesString) = value.split("; ", limit = 2)
                            val choices = choicesString.split("|")
                            variables[name] = Variable(description, choices)
                            offset += v.size()
                        }
                        callback.onSetVariables(variables)
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE -> {
                        val updated = callback.onGetVariableUpdate()
                        data.setByte(0, if (updated) 1 else 0)
                        return updated
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE -> {
                        return false // FIXME
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_LOG_INTERFACE -> {
                        val logInterface = callback.onGetLogInterface() ?: return false
                        val logCb = object : LibRetro.retro_log_printf_t {
                            override fun invoke(log_level: Int, fmt: String, arg: Pointer) {
                                val size = LibC.INSTANCE.vsnprintf(null, 0, fmt, arg)
                                if (size <= 0) return

                                val byteBuffer = ByteBuffer.allocateDirect(size + 1)
                                LibC.INSTANCE.vsnprintf(byteBuffer, byteBuffer.capacity(), fmt, arg)

                                val bytes = ByteArray(size)
                                byteBuffer.get(bytes)

                                val message = String(bytes)

                                val newLevel = when (log_level) {
                                    LibRetro.retro_log_level.RETRO_LOG_DEBUG -> LogLevel.DEBUG
                                    LibRetro.retro_log_level.RETRO_LOG_INFO -> LogLevel.INFO
                                    LibRetro.retro_log_level.RETRO_LOG_WARN -> LogLevel.WARN
                                    LibRetro.retro_log_level.RETRO_LOG_ERROR -> LogLevel.ERROR
                                    else -> throw IllegalArgumentException()
                                }

                                logInterface.onLogMessage(newLevel, message)
                            }
                        }

                        logPrintfCb = logCb

                        // Can't use our callback with libretro directly because
                        // JNA does not support variadic function callbacks.
                        LibRetrograde.setLogCallback(logCb)
                        val retrogradeLog = LibRetrograde.getRetroLogPrintf()

                        val logCallback = LibRetro.retro_log_callback(data)
                        logCallback.log = retrogradeLog
                        logCallback.write()

                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY -> {
                        val directory = callback.onGetSaveDirectory()
                        if (directory != null) {
                            val ref = PointerByReference(data)
                            ref.value.setPointer(0, NativeString(directory).pointer)
                        }
                        return directory != null
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO -> {
                        callback.onSetSystemAvInfo(SystemAVInfo.create(LibRetro.retro_system_av_info(data)))
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_CONTROLLER_INFO -> {
                        val infos = mutableListOf<ControllerInfo>()
                        var offset = 0L
                        while (true) {
                            val info = LibRetro.retro_controller_info(data.share(offset))
                            info.types ?: break
                            val descriptions = LibRetro.retro_controller_description(info.types)
                                    .toArray(info.num_types!!.toInt())
                                    .map { it as LibRetro.retro_controller_description }
                            val types = mutableListOf<ControllerDescription>()
                            for (desc in descriptions) {
                                val device = Device.fromValue(desc.id!!.toInt())
                                if (device != null) {
                                    types.add(ControllerDescription(desc.desc!!, device))
                                }
                            }
                            infos.add(ControllerInfo(types.toList()))
                            offset += info.size()
                        }
                        callback.onSetControllerInfo(infos.toList())
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_GEOMETRY -> {
                        val geometry = LibRetro.retro_game_geometry(data)
                        callback.onSetGeometry(GameGeometry.create(geometry))
                        return true
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS -> {
                        return false // FIXME
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_MEMORY_MAPS -> {
                        callback.onSetMemoryMaps()
                        return false
                    }
                    LibRetro.RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS -> {
                        val supportsAchievements = data.getByte(0).toInt() == 1
                        callback.onSetSupportAchievements(supportsAchievements)
                        return true
                    }
                    else -> {
                        callback.onUnsupportedCommand(cmd.toInt())
                        return false
                    }
                }
            } catch (error: Throwable) {
                callback.onUnhandledException(error)
                return false
            }
        }
    }
}
