package inspector.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import inspector.util.PackageTools.EXTENSION_FEATURE
import inspector.util.PackageTools.LIB_VERSION_MAX
import inspector.util.PackageTools.LIB_VERSION_MIN
import inspector.util.PackageTools.METADATA_SOURCE_CLASS
import inspector.util.PackageTools.dex2jar
import inspector.util.PackageTools.getPackageInfo
import inspector.util.PackageTools.loadExtensionSources
import mu.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Extension {
    private val logger = KotlinLogging.logger {}

    suspend fun installApk(
        tmpDir: File,
        fetcher: suspend () -> File
    ): Pair<String, List<HttpSource>> {
        val apkFile = fetcher()

        val jarFile = File(tmpDir, "${apkFile.nameWithoutExtension}.jar")

        val packageInfo = getPackageInfo(apkFile.absolutePath)

        if (!packageInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }) {
            throw Exception("This apk is not a Tachiyomi extension")
        }

        // Validate lib version
        val libVersion = packageInfo.versionName.substringBeforeLast('.').toDouble()
        if (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
            throw Exception(
                "Lib version is $libVersion, while only versions " +
                    "$LIB_VERSION_MIN to $LIB_VERSION_MAX are allowed"
            )
        }

        val className = packageInfo.packageName + packageInfo.applicationInfo.metaData.getString(
            METADATA_SOURCE_CLASS
        )

        logger.trace("Main class for extension is $className")

        dex2jar(apkFile, jarFile)
        extractAssetsFromApk(apkFile, jarFile)

        val instance = loadExtensionSources(jarFile, className)

        // collect sources from the extension
        return packageInfo.packageName to when (instance) {
            is Source -> listOf(instance).filterIsInstance<HttpSource>()
            is SourceFactory -> instance.createSources().filterIsInstance<HttpSource>()
            else -> throw RuntimeException("Unknown source class type! ${instance.javaClass}")
        }
    }

    private fun extractAssetsFromApk(apkFile: File, jarFile: File) {
        val tempJarFile = File("${jarFile.parent}/${jarFile.nameWithoutExtension}_temp.jar")
        val zos = ZipOutputStream(FileOutputStream(tempJarFile))

        ZipInputStream(jarFile.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }
                .filterNot { it.name.startsWith("META-INF/") }
                .forEach {
                    zos.putNextEntry(ZipEntry(it.name))
                    zis.copyTo(zos)
                }
            zis.close()
        }

        ZipInputStream(apkFile.inputStream()).use { zis ->
            generateSequence { zis.nextEntry }
                .filter { it.name.startsWith("assets/") }
                .forEach {
                    zos.putNextEntry(ZipEntry(it.name))
                    zis.copyTo(zos)
                }
            zis.close()
        }

        zos.close()
        jarFile.delete()
        tempJarFile.renameTo(jarFile)
    }
}
