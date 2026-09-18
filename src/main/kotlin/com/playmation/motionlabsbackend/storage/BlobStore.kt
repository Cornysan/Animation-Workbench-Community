package com.playmation.motionlabsbackend.storage

import com.playmation.motionlabsbackend.config.PortalProperties
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Dateiablage für hochgeladene Clips und Vorschauen, getrennt von den
 * Metadaten (Konzept §4). Nie öffentlich: ausgeliefert wird nur über den
 * FileController nach Prüfung von Signatur und Status - ein Takedown wirkt so
 * sofort, auch auf schon verteilte Links.
 *
 * Schlüssel sind zufällige UUIDs; kein Nutzereingabe-Teil landet im Pfad.
 */
interface BlobStore {
    fun put(bytes: ByteArray): String
    fun open(key: String): InputStream
    fun size(key: String): Long
}

@Component
class FileSystemBlobStore(properties: PortalProperties) : BlobStore {
    private val root: Path = Path.of(properties.storage.directory).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override fun put(bytes: ByteArray): String {
        val key = UUID.randomUUID().toString().replace("-", "")
        val target = pathOf(key)
        Files.createDirectories(target.parent)

        //  Erst vollständig schreiben, dann umbenennen - ein Absturz hinterlässt
        //  keine halbe Datei unter einem gültigen Schlüssel.
        val temp = Files.createTempFile(target.parent, key, ".tmp")
        Files.write(temp, bytes)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        return key
    }

    override fun open(key: String): InputStream = Files.newInputStream(pathOf(key))

    override fun size(key: String): Long = Files.size(pathOf(key))

    private fun pathOf(key: String): Path {
        require(key.length == 32 && key.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid blob key" }
        return root.resolve(key.substring(0, 2)).resolve(key)
    }
}
