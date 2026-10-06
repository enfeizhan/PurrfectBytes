package com.purrfectbytes.android.services

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The FFmpeg that is installed on this computer, standing in for the one of the phone.
 * It is given the same arguments.
 */
internal object DesktopFFmpeg {

    private fun find(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private val ffmpeg = find("ffmpeg")
    private val ffprobe = find("ffprobe")

    val isInstalled: Boolean get() = ffmpeg != null && ffprobe != null

    private class Output(val exitCode: Int, val text: String, val errors: String)

    private fun execute(command: List<String>): Output {
        val errors = File.createTempFile("ffmpeg", ".log")
        try {
            val process = ProcessBuilder(command).redirectError(errors).start()
            val text = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            check(process.waitFor(120, TimeUnit.SECONDS)) { "timed out: $command" }
            return Output(process.exitValue(), text, errors.readText())
        } finally {
            errors.delete()
        }
    }

    val runner = FFmpegRunner { arguments ->
        val result = execute(listOf(ffmpeg!!, "-hide_banner", "-loglevel", "error") + arguments)
        if (result.exitCode != 0) throw VideoException("FFmpeg could not encode the video. ${result.errors}")
    }

    /** How long [file] plays, in seconds. */
    fun seconds(file: File): Double =
        execute(
            listOf(ffprobe!!, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.path)
        ).text.toDouble()

    /** A tone as an MP3 like the ones Edge TTS sends: 24 kHz, mono, 48 kbit/s. */
    fun tone(seconds: Double, file: File): File {
        val result = execute(
            listOf(
                ffmpeg!!, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
                "-ar", "24000", "-ac", "1", "-b:a", "48k", file.path
            )
        )
        check(result.exitCode == 0) { result.errors }
        return file
    }
}
