package dev.ipf.whitenoise.android.media

// Builds small synthetic ISO BMFF movies whose structure each test controls, for #3376.

/** Header times written into every synthetic `mvhd`, `tkhd` and `mdhd`, so zeroing them is observable. */
internal const val SYNTHETIC_TIME = 0x7A7A7A7AL

/** Sample-size table forms the builder can write. */
internal enum class SizeTable {
    Variable,
    Constant,
    Compact4,
    Compact8,
    Compact16,
}

/** One synthetic track: its handler, its samples and how its sample tables are written. */
internal data class SyntheticTrack(
    val handler: String,
    val samples: List<ByteArray>,
    val sizes: SizeTable = SizeTable.Variable,
    val wideOffsets: Boolean = false,
    val selfContained: Boolean = true,
    val handlerName: String = "WNPrivateHandler",
)

/** A built movie and the byte range of every sample, per track in order. */
internal data class SyntheticMovie(
    val bytes: ByteArray,
    val samples: List<List<IntRange>>,
)

/**
 * Builds an `ftyp` of [brands] (major first, then the compatible brands, none when empty), then `mdat` holding every
 * track's samples back to back, then `moov` (or `moov` before `mdat` with [mediaDataLast], whose `mdat` then runs to
 * the end of the file through a zero size). [movieExtras] are appended to the movie box and [topLevelExtras] to the
 * file.
 */
internal fun syntheticMovie(
    tracks: List<SyntheticTrack>,
    movieExtras: List<ByteArray> = emptyList(),
    topLevelExtras: List<ByteArray> = emptyList(),
    mediaDataLast: Boolean = false,
    brands: List<String> = listOf("isom", "isom", "iso2"),
): SyntheticMovie {
    val ftyp =
        if (brands.isEmpty()) {
            ByteArray(0)
        } else {
            isoBox("ftyp", ascii(brands.first()), beBytes(512, 4), *brands.drop(1).map(::ascii).toTypedArray())
        }
    val sampleBytes = concat(*tracks.flatMap { it.samples }.toTypedArray())
    val probe = movieBox(tracks, movieExtras, 0L)
    val mediaStart = if (mediaDataLast) ftyp.size + probe.size + 8 else ftyp.size + 8
    val moov = movieBox(tracks, movieExtras, mediaStart.toLong())
    val bytes =
        if (mediaDataLast) {
            concat(ftyp, moov, beBytes(0L, 4), ascii("mdat"), sampleBytes, *topLevelExtras.toTypedArray())
        } else {
            concat(ftyp, isoBox("mdat", sampleBytes), moov, *topLevelExtras.toTypedArray())
        }
    var offset = mediaStart
    val ranges =
        tracks.map { track ->
            track.samples.map { sample -> (offset until offset + sample.size).also { offset += sample.size } }
        }
    return SyntheticMovie(bytes, ranges)
}

/** The movie box for [tracks], whose samples start at [mediaStart] in track order. */
private fun movieBox(
    tracks: List<SyntheticTrack>,
    extras: List<ByteArray>,
    mediaStart: Long,
): ByteArray {
    var offset = mediaStart
    val traks =
        tracks.mapIndexed { index, track ->
            trackBox(index + 1, track, offset).also { offset += track.samples.sumOf { it.size } }
        }
    return isoBox("moov", movieHeader(), *extras.toTypedArray(), *traks.toTypedArray())
}

/** A version 0 `mvhd` with [SYNTHETIC_TIME] as both times. */
internal fun movieHeader(): ByteArray =
    isoFullBox(
        "mvhd",
        0,
        0,
        beBytes(SYNTHETIC_TIME, 4),
        beBytes(SYNTHETIC_TIME, 4),
        beBytes(1000, 4),
        beBytes(1000, 4),
        ByteArray(80),
    )

/** One `trak` whose single chunk starts at [chunkOffset]. Its `tkhd` uses version 1 to cover 64-bit times. */
internal fun trackBox(
    id: Int,
    track: SyntheticTrack,
    chunkOffset: Long,
): ByteArray {
    val tkhd =
        isoFullBox(
            "tkhd",
            1,
            3,
            beBytes(SYNTHETIC_TIME, 8),
            beBytes(SYNTHETIC_TIME, 8),
            beBytes(id.toLong(), 4),
            ByteArray(4),
            beBytes(1000, 8),
            ByteArray(16),
            beBytes(0x00010000L, 4),
            ByteArray(12),
            beBytes(0x00010000L, 4),
            ByteArray(12),
            beBytes(0x40000000L, 4),
            beBytes(48L shl 16, 4),
            beBytes(32L shl 16, 4),
        )
    val time = beBytes(SYNTHETIC_TIME, 4)
    val mdhd = isoFullBox("mdhd", 0, 0, time, time, beBytes(1000, 4), beBytes(1000, 4), ByteArray(4))
    val name = ascii(track.handlerName + "\u0000")
    val hdlr = isoFullBox("hdlr", 0, 0, ByteArray(4), ascii(track.handler), ByteArray(12), name)
    val urlEntry =
        if (track.selfContained) {
            isoFullBox("url ", 0, 1)
        } else {
            isoFullBox("url ", 0, 0, ascii("file:///WNPrivatePath\u0000"))
        }
    val dinf = isoBox("dinf", isoFullBox("dref", 0, 0, beBytes(1, 4), urlEntry))
    val stbl = isoBox("stbl", sampleDescriptions(track.handler), *sampleTables(track, chunkOffset))
    return isoBox("trak", tkhd, isoBox("mdia", mdhd, hdlr, isoBox("minf", dinf, stbl)))
}

/** One sample entry: a full visual entry with a vendor and compressor name for video, a stub otherwise. */
private fun sampleDescriptions(handler: String): ByteArray {
    val entry =
        if (handler == "vide") {
            val compressor = ascii("WNPrivateCompressor").copyOf(32)
            val size = concat(beBytes(48, 2), beBytes(32, 2))
            val head = concat(ByteArray(6), beBytes(1, 2), ByteArray(4), ascii("WNVN"), ByteArray(8))
            isoBox("avc1", head, size, ByteArray(14), compressor, ByteArray(4))
        } else {
            isoBox("mett", ByteArray(6), beBytes(1, 2), ascii("application/x-private\u0000"))
        }
    return isoFullBox("stsd", 0, 0, beBytes(1, 4), entry)
}

/** `stts`, `stsc`, the sample-size table and the chunk-offset table for one chunk holding every sample. */
private fun sampleTables(
    track: SyntheticTrack,
    chunkOffset: Long,
): Array<ByteArray> {
    val count = track.samples.size.toLong()
    val stts = isoFullBox("stts", 0, 0, beBytes(1, 4), beBytes(count, 4), beBytes(1, 4))
    val stsc = isoFullBox("stsc", 0, 0, beBytes(1, 4), beBytes(1, 4), beBytes(count, 4), beBytes(1, 4))
    val offsets =
        if (track.wideOffsets) {
            isoFullBox("co64", 0, 0, beBytes(1, 4), beBytes(chunkOffset, 8))
        } else {
            isoFullBox("stco", 0, 0, beBytes(1, 4), beBytes(chunkOffset, 4))
        }
    return arrayOf(stts, stsc, sizeTable(track), offsets)
}

/** The sample sizes in the requested form. */
private fun sizeTable(track: SyntheticTrack): ByteArray {
    val sizes = track.samples.map { it.size.toLong() }
    val count = beBytes(sizes.size.toLong(), 4)
    return when (track.sizes) {
        SizeTable.Variable ->
            isoFullBox("stsz", 0, 0, beBytes(0, 4), count, *sizes.map { beBytes(it, 4) }.toTypedArray())
        SizeTable.Constant -> isoFullBox("stsz", 0, 0, beBytes(sizes.distinct().single(), 4), count)
        SizeTable.Compact4 -> {
            val packed = sizes.chunked(2).map { pair -> ((pair[0] shl 4) or (pair.getOrElse(1) { 0L })).toByte() }
            isoFullBox("stz2", 0, 0, byteArrayOf(0, 0, 0, 4), count, packed.toByteArray())
        }
        SizeTable.Compact8 ->
            isoFullBox("stz2", 0, 0, byteArrayOf(0, 0, 0, 8), count, sizes.map { it.toByte() }.toByteArray())
        SizeTable.Compact16 ->
            isoFullBox("stz2", 0, 0, byteArrayOf(0, 0, 0, 16), count, *sizes.map { beBytes(it, 2) }.toTypedArray())
    }
}

/** Samples with the given [sizes], each filled with [fill] plus its index so every sample differs. */
internal fun samples(
    sizes: List<Int>,
    fill: Int,
): List<ByteArray> = sizes.mapIndexed { index, size -> ByteArray(size) { (fill + index).toByte() } }
