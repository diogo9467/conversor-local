// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.diogo.conversorlocal.conversion

import android.content.Context
import dev.diogo.officelocal.OfficeBridge
import java.io.File
import java.util.UUID

/** Matching COKit engine; never load the legacy LibreOfficeKit JNI wrappers. */
object OfficeEngine {
    @Synchronized fun convert(context: Context, input: File, output: File) {
        val data = File(context.filesDir, "conversion-runtime")
        val marker = File(data, "assets-version")
        val version = org.libreoffice.androidlib.BuildConfig.GIT_COMMIT
        if (!marker.exists() || marker.readText() != version) {
            unpack(context, "unpack", data)
            File(data, "etc/fonts/fonts.conf").apply {
                writeText(readText().replace("/data/data/dev.diogo.officelocal", data.absolutePath))
            }
            marker.writeText(version)
        }
        val profile = File(context.cacheDir, "jobs/profile-${UUID.randomUUID()}").apply { mkdirs() }
        OfficeBridge.convert(data.absolutePath, context.cacheDir.absolutePath,
                context.applicationInfo.sourceDir, context.assets, profile.toURI().toString(),
                input.toURI().toString(), output.toURI().toString())
        check(output.isFile && output.length() > 0) { "O motor não gerou um PDF." }
        // The native kit can still use its profile until the worker process exits.
        // MainViewModel cleans only cache/jobs after the worker has stopped.
    }
    private fun unpack(context: Context, asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isNotEmpty()) {
            check(dest.isDirectory || dest.mkdirs())
            children.forEach { unpack(context, "$asset/$it", File(dest, it)) }
        } else {
            dest.parentFile!!.mkdirs()
            context.assets.open(asset).use { source -> dest.outputStream().use(source::copyTo) }
        }
    }
}
