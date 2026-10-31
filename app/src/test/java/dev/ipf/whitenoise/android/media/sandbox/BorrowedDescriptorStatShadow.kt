package dev.ipf.whitenoise.android.media.sandbox

import android.os.ParcelFileDescriptor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException

/** Robolectric 4.17 duplicates can have a raw descriptor but no RandomAccessFile for getStatSize(). */
@Implements(ParcelFileDescriptor::class)
class BorrowedDescriptorStatShadow : ShadowParcelFileDescriptor() {
    // FileInputStream(FileDescriptor) borrows this descriptor without an owning cleaner or a new descriptor.
    // Keep the metadata view alive; only inherited ParcelFileDescriptor.close owns descriptor retirement.
    private var borrowed: FileInputStream? = null

    @Implementation
    @Synchronized
    override fun getStatSize(): Long =
        try {
            val view = borrowed ?: FileInputStream(super.getFileDescriptor()).also { borrowed = it }
            view.channel.size()
        } catch (_: IOException) {
            -1L
        }
}
