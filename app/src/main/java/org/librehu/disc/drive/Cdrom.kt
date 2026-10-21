package org.librehu.disc.drive

/** CD-ROM ioctls of the kernel's SCSI CD-ROM driver (`sr`), in `librehu_disc.so`. Negative results are -errno. */
object Cdrom {
    init {
        System.loadLibrary("librehu_disc")
    }

    // linux/cdrom.h: CDROM_DRIVE_STATUS results.
    const val CDS_NO_INFO = 0
    const val CDS_NO_DISC = 1
    const val CDS_TRAY_OPEN = 2
    const val CDS_DRIVE_NOT_READY = 3
    const val CDS_DISC_OK = 4

    // CDROM_DISC_STATUS results.
    const val CDS_AUDIO = 100
    const val CDS_DATA_1 = 101
    const val CDS_DATA_2 = 102
    const val CDS_XA_2_1 = 103
    const val CDS_XA_2_2 = 104
    const val CDS_MIXED = 105

    /** Control bit of a data track in the table of contents. */
    const val DATA_TRACK = 0x04

    /** Bytes of an audio sector: 1/75 s of 16-bit stereo PCM at 44.1 kHz. */
    const val FRAME_BYTES = 2352
    const val FRAMES_PER_SECOND = 75

    @JvmStatic external fun nativeOpen(path: String): Int

    @JvmStatic external fun nativeClose(fd: Int)

    @JvmStatic external fun driveStatus(fd: Int): Int

    @JvmStatic external fun discStatus(fd: Int): Int

    @JvmStatic external fun mediaChanged(fd: Int): Int

    /** [first, last, (number, start LBA, control) per track, lead-out LBA], or null. */
    @JvmStatic external fun readToc(fd: Int): IntArray?

    /** Bytes read into [buf] (frames × [FRAME_BYTES]), or -errno. */
    @JvmStatic external fun readAudio(
        fd: Int,
        lba: Int,
        frames: Int,
        buf: ByteArray,
    ): Int

    @JvmStatic external fun capacity(fd: Int): Long

    @JvmStatic external fun eject(fd: Int): Int

    @JvmStatic external fun closeTray(fd: Int): Int

    /** The tray no longer closes when something opens the drive (mount): only [closeTray] closes it. */
    @JvmStatic external fun noAutoClose(fd: Int): Int
}
