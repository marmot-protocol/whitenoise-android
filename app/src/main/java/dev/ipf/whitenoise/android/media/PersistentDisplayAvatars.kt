package dev.ipf.whitenoise.android.media

import android.util.AtomicFile
import dev.ipf.whitenoise.android.core.nostr.sha256
import dev.ipf.whitenoise.android.core.nostr.toHex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Identifies one rendered derivative of the current account and selected source. */
internal data class DisplayAvatarKey(
    val accountRef: String,
    val groupIdHex: String,
    val target: String,
    val reference: String,
    val revision: ULong,
) {
    fun canonical(): ByteArray =
        encodeFields {
            writeInt(DISPLAY_AVATAR_KEY_VERSION)
            write(sha256(accountRef.encodeToByteArray()))
            write(sha256(groupIdHex.lowercase().encodeToByteArray()))
            write(sha256(target.encodeToByteArray()))
            write(sha256(reference.encodeToByteArray()))
            writeLong(revision.toLong())
            writeInt(DISPLAY_AVATAR_MAX_EDGE)
        }
}

internal data class DisplayAvatarGeneration(
    val accountRef: String,
    val all: Long,
    val account: Long,
)

/** A disposable, account-scoped store for authenticated display pixels. All methods that touch disk run off main. */
@Suppress("TooManyFunctions") // Serialization and invalidation share the same file publication lock.
internal class PersistentDisplayAvatars(
    private val root: File,
    private val keyProvider: EnvelopeCacheKeyProvider,
) {
    private val lock = Any()
    private val generationLock = Any()
    private val accountGenerations = mutableMapOf<String, Long>()
    private val retiredSelections = mutableMapOf<String, MutableSet<DisplayAvatarKey>>()
    private var allGeneration = 0L

    /** Capture before starting an asynchronous decode or disk read. */
    fun generation(accountRef: String): DisplayAvatarGeneration =
        synchronized(generationLock) {
            DisplayAvatarGeneration(accountRef, allGeneration, accountGenerations.getOrPut(accountRef) { 0 })
        }

    fun publishIfCurrent(
        generation: DisplayAvatarGeneration,
        publish: () -> Unit,
    ) {
        synchronized(generationLock) {
            if (isCurrent(generation.accountRef, generation)) publish()
        }
    }

    private fun isCurrent(
        accountRef: String,
        generation: DisplayAvatarGeneration,
    ): Boolean =
        synchronized(generationLock) {
            generation.accountRef == accountRef &&
                generation.all == allGeneration &&
                generation.account == (accountGenerations[accountRef] ?: 0)
        }

    /** Invalidation is visible before the blocking deletion begins. */
    fun retirePending(accountRef: String) {
        synchronized(generationLock) {
            accountGenerations[accountRef] = (accountGenerations[accountRef] ?: 0) + 1
        }
    }

    /** Records disk evictions before allowing work under the next account generation. */
    fun retireSelections(
        accountRef: String,
        keys: List<DisplayAvatarKey>,
    ) {
        synchronized(generationLock) {
            retirePending(accountRef)
            retiredSelections.getOrPut(accountRef) { mutableSetOf() }.addAll(keys)
        }
    }

    /** Off-main presence hint for writeback. Rendering always requires an authenticated read. */
    fun hasEntry(key: DisplayAvatarKey): Boolean =
        synchronized(lock) {
            evictRetiredSelections(key.accountRef)
            entry(key, key.canonical()).baseFile.isFile
        }

    fun read(
        key: DisplayAvatarKey,
        generation: DisplayAvatarGeneration,
    ): DisplayAvatarPixels? {
        val canonical = key.canonical()
        val encoded =
            synchronized(lock) {
                if (!isCurrent(key.accountRef, generation)) return@synchronized null
                open(entry(key, canonical), ENTRY_MAGIC, canonical, DisplayAvatarCodec.MAX_ENCODED_BYTES)
            } ?: return null
        val pixels = DisplayAvatarCodec.decode(encoded)
        return if (isCurrent(key.accountRef, generation)) {
            pixels
        } else {
            pixels?.bitmap?.recycle()
            null
        }
    }

    fun write(
        key: DisplayAvatarKey,
        pixels: DisplayAvatarPixels,
        generation: DisplayAvatarGeneration,
    ) {
        val encoded = DisplayAvatarCodec.encode(pixels) ?: return
        val canonical = key.canonical()
        synchronized(lock) {
            evictRetiredSelections(key.accountRef)
            if (isCurrent(key.accountRef, generation)) {
                seal(entry(key, canonical), ENTRY_MAGIC, canonical, encoded)
            }
        }
    }

    /** Reads one pack, selecting only exact keys from the current model. */
    fun readStartupPack(
        accountRef: String,
        keys: Collection<DisplayAvatarKey>,
        generation: DisplayAvatarGeneration,
    ): Map<DisplayAvatarKey, DisplayAvatarPixels> {
        val encoded =
            synchronized(lock) {
                if (!isCurrent(accountRef, generation) || keys.isEmpty()) return@synchronized null
                open(pack(accountRef), PACK_MAGIC, packAssociatedData(accountRef), MAX_PACK_BYTES)
            } ?: return emptyMap()
        val selected = keys.filter { it.accountRef == accountRef }.associateBy { sha256(it.canonical()).toHex() }
        val matches = unpack(encoded, selected)
        return if (isCurrent(accountRef, generation)) {
            matches
        } else {
            matches.values.forEach { it.bitmap.recycle() }
            emptyMap()
        }
    }

    /** Rebuilds a pack from already published entries while their common owner remains current. */
    fun writeStartupPack(
        accountRef: String,
        keys: List<DisplayAvatarKey>,
        generation: DisplayAvatarGeneration,
    ) {
        synchronized(lock) {
            evictRetiredSelections(accountRef)
            if (!isCurrent(accountRef, generation)) return
            val entries =
                keys
                    .asSequence()
                    .filter { it.accountRef == accountRef }
                    .distinct()
                    .take(PACK_COUNT)
                    .mapNotNull { key ->
                        val canonical = key.canonical()
                        open(entry(key, canonical), ENTRY_MAGIC, canonical, DisplayAvatarCodec.MAX_ENCODED_BYTES)
                            ?.let { canonical to it }
                    }.toList()
            val payload =
                encodeFields {
                    writeShort(entries.size)
                    entries.forEach { (canonical, pixels) ->
                        writeShort(canonical.size)
                        write(canonical)
                        writeInt(pixels.size)
                        write(pixels)
                    }
                }
            seal(pack(accountRef), PACK_MAGIC, packAssociatedData(accountRef), payload)
        }
    }

    private fun unpack(
        encoded: ByteArray,
        selected: Map<String, DisplayAvatarKey>,
    ): Map<DisplayAvatarKey, DisplayAvatarPixels> =
        try {
            DataInputStream(encoded.inputStream()).use { input ->
                val count = input.readUnsignedShort()
                if (count > PACK_COUNT) throw IOException("Display avatar pack exceeds its entry limit")
                buildMap {
                    repeat(count) {
                        val keyBytes = input.readUnsignedShort()
                        if (keyBytes != CANONICAL_BYTES) throw IOException("Invalid display avatar pack key size")
                        val canonical = ByteArray(keyBytes).also(input::readFully)
                        val size = input.readInt()
                        if (size !in 1..DisplayAvatarCodec.MAX_ENCODED_BYTES) {
                            throw IOException("Invalid display avatar size")
                        }
                        val bytes = ByteArray(size).also(input::readFully)
                        selected[sha256(canonical).toHex()]?.let { key ->
                            DisplayAvatarCodec.decode(bytes)?.let { put(key, it) }
                        }
                    }
                    if (input.read() != -1) throw IOException("Invalid trailing bytes in display avatar pack")
                }
            }
        } catch (_: IOException) {
            emptyMap()
        }

    fun clearAccount(accountRef: String): Boolean =
        synchronized(lock) {
            retirePending(accountRef)
            accountDir(accountRef).deleteRecursively()
        }

    /** Removes obsolete files before a newer producer can replace them. */
    fun evictRetiredSelections(accountRef: String) {
        synchronized(lock) {
            val keys = synchronized(generationLock) { retiredSelections.remove(accountRef) } ?: return
            pack(accountRef).delete()
            keys.forEach { entry(it, it.canonical()).delete() }
        }
    }

    fun evictGroup(
        accountRef: String,
        groupIdHex: String,
    ): Boolean =
        synchronized(lock) {
            retirePending(accountRef)
            val startupPack = pack(accountRef)
            startupPack.delete()
            groupDir(accountRef, groupIdHex).deleteRecursively() && !startupPack.baseFile.exists()
        }

    fun clearAll() {
        synchronized(lock) {
            retireAllPending()
            keyProvider.clear()
        }
    }

    private fun retireAllPending() {
        synchronized(generationLock) {
            allGeneration++
            accountGenerations.clear()
        }
    }

    /** Repairs interrupted cleanup. [runIfCurrent] guards generation changes, and I/O uses the cache lock. */
    fun retainAccounts(
        accountRefs: Set<String>,
        runIfCurrent: (() -> Unit) -> Boolean,
    ) {
        val kept = accountRefs.mapTo(mutableSetOf()) { accountDir(it).name }
        synchronized(lock) {
            if (kept.isEmpty()) {
                if (runIfCurrent(::retireAllPending)) keyProvider.clear()
                return
            }
            val orphaned = root.listFiles()?.filter { it.isDirectory && it.name !in kept }.orEmpty()
            val accepted =
                runIfCurrent {
                    // Capture registers accounts even if a write has not yet created their directory.
                    synchronized(generationLock) {
                        accountGenerations.keys.filterNot(accountRefs::contains).forEach(::retirePending)
                    }
                }
            if (accepted) orphaned.forEach { if (runIfCurrent {}) check(it.deleteRecursively()) }
        }
    }

    fun residentBytes(): Long = synchronized(lock) { cacheFiles().sumOf(File::length) }

    /** Runs after publication. Point reads never scan the cache directory. */
    fun prune() {
        synchronized(lock) {
            val files = cacheFiles()
            var total = files.sumOf(File::length)
            files.sortedBy(File::lastModified).forEach { file ->
                if (total <= MAX_BYTES) return
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }

    private fun cacheFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()

    private fun accountDir(accountRef: String): File {
        val accountHash = sha256(accountRef.encodeToByteArray()).toHex()
        return File(root, "acct-$accountHash")
    }

    private fun groupDir(
        accountRef: String,
        groupIdHex: String,
    ): File = File(accountDir(accountRef), sha256(groupIdHex.lowercase().encodeToByteArray()).toHex())

    private fun entry(
        key: DisplayAvatarKey,
        canonical: ByteArray,
    ): AtomicFile = AtomicFile(File(groupDir(key.accountRef, key.groupIdHex), "${sha256(canonical).toHex()}.dav1"))

    private fun pack(accountRef: String): AtomicFile = AtomicFile(File(accountDir(accountRef), "startup_pack_v1.enc"))

    private fun packAssociatedData(accountRef: String): ByteArray =
        encodeFields {
            writeUTF("startup-pack-v1")
            write(sha256(accountRef.encodeToByteArray()))
        }

    @Suppress("ReturnCount")
    private fun open(
        file: AtomicFile,
        magic: Int,
        aad: ByteArray,
        maxPayload: Int,
    ): ByteArray? {
        val size = file.baseFile.length()
        if (size !in (ENVELOPE_OVERHEAD + 1).toLong()..(ENVELOPE_OVERHEAD + maxPayload).toLong()) return null
        return try {
            val stored = ByteArray(size.toInt())
            DataInputStream(file.openRead()).use { input ->
                input.readFully(stored)
                if (input.read() != -1) return null
            }
            val header = ByteBuffer.wrap(stored)
            if (header.int != magic || header.get() != ENVELOPE_VERSION) return null
            val iv = ByteArray(IV_BYTES)
            header.get(iv)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreate(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(stored, header.position(), header.remaining()).also {
                val now = System.currentTimeMillis()
                if (now - file.baseFile.lastModified() >= TOUCH_INTERVAL_MS) file.baseFile.setLastModified(now)
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun seal(
        file: AtomicFile,
        magic: Int,
        aad: ByteArray,
        payload: ByteArray,
    ) {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreate())
            cipher.updateAAD(aad)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) return
            val encoded =
                ByteBuffer
                    .allocate(ENVELOPE_OVERHEAD + payload.size)
                    .putInt(magic)
                    .put(ENVELOPE_VERSION)
                    .put(iv)
                    .put(cipher.doFinal(payload))
                    .array()
            val out = file.startWrite()
            try {
                out.write(encoded)
                file.finishWrite(out)
            } catch (failure: IOException) {
                file.failWrite(out)
                throw failure
            }
        } catch (_: GeneralSecurityException) {
            // The ordinary local decode remains available.
        } catch (_: ProviderException) {
            // A temporarily unavailable keystore is a cache miss.
        } catch (_: IOException) {
            // A failed cache write does not change the source.
        }
    }

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val ENTRY_MAGIC = 0x574E4131
        const val PACK_MAGIC = 0x574E5031
        const val ENVELOPE_VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val ENVELOPE_OVERHEAD = 5 + IV_BYTES + TAG_BITS / Byte.SIZE_BITS
        const val PACK_COUNT = 24
        const val MAX_PACK_BYTES = 2 + PACK_COUNT * (6 + CANONICAL_BYTES + DisplayAvatarCodec.MAX_ENCODED_BYTES)
        const val MAX_BYTES = 48L * 1024 * 1024
        const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
    }
}

private const val DISPLAY_AVATAR_KEY_VERSION = 1
private const val CANONICAL_BYTES = 4 * 32 + 16

private fun encodeFields(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use(write) }.toByteArray()
