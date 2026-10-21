/*
 * CD-ROM ioctls of the Linux SCSI CD-ROM driver (sr, /dev/block/sr0) for LibreHU Disc: drive and disc status,
 * table of contents, raw audio sectors (CD-DA, 2352 bytes each), capacity, eject. Java cannot call ioctl().
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <linux/cdrom.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define JNI_FN(name) Java_org_librehu_disc_drive_Cdrom_##name

/* Opened without blocking: the drive opens without a disc, the status ioctls then say why. */
JNIEXPORT jint JNICALL JNI_FN(nativeOpen)(JNIEnv *env, jclass cls, jstring path)
{
	(void)cls;
	const char *p = (*env)->GetStringUTFChars(env, path, NULL);
	int fd;

	if (!p)
		return -ENOMEM;
	fd = open(p, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
	(*env)->ReleaseStringUTFChars(env, path, p);
	return fd < 0 ? -errno : fd;
}

JNIEXPORT void JNICALL JNI_FN(nativeClose)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	if (fd >= 0)
		close(fd);
}

/* CDS_NO_INFO, CDS_NO_DISC, CDS_TRAY_OPEN, CDS_DRIVE_NOT_READY, CDS_DISC_OK, or -errno. */
JNIEXPORT jint JNICALL JNI_FN(driveStatus)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	int r = ioctl(fd, CDROM_DRIVE_STATUS, CDSL_CURRENT);

	return r < 0 ? -errno : r;
}

/* CDS_AUDIO, CDS_DATA_1, CDS_DATA_2, CDS_XA_2_1, CDS_XA_2_2, CDS_MIXED, CDS_NO_INFO..., or -errno. */
JNIEXPORT jint JNICALL JNI_FN(discStatus)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	int r = ioctl(fd, CDROM_DISC_STATUS, 0);

	return r < 0 ? -errno : r;
}

/* 1 when the disc changed since the last call, 0 otherwise, or -errno. */
JNIEXPORT jint JNICALL JNI_FN(mediaChanged)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	int r = ioctl(fd, CDROM_MEDIA_CHANGED, CDSL_CURRENT);

	return r < 0 ? -errno : r;
}

/*
 * Table of contents, as LBAs: [first track, last track, then per track (number, start LBA, control bits),
 * then the lead-out LBA]. Control bit 0x04 (CDROM_DATA_TRACK) marks a data track. NULL on error.
 */
JNIEXPORT jintArray JNICALL JNI_FN(readToc)(JNIEnv *env, jclass cls, jint fd)
{
	(void)cls;
	struct cdrom_tochdr hdr;
	struct cdrom_tocentry e;
	jint *v;
	jintArray out;
	int first, last, n, i, k = 0;

	if (ioctl(fd, CDROMREADTOCHDR, &hdr) < 0)
		return NULL;
	first = hdr.cdth_trk0;
	last = hdr.cdth_trk1;
	if (first < 1 || last < first || last > 99)
		return NULL;
	n = last - first + 1;
	v = calloc(2 + 3 * n + 1, sizeof(*v));
	if (!v)
		return NULL;
	v[k++] = first;
	v[k++] = last;
	for (i = first; i <= last; i++) {
		memset(&e, 0, sizeof(e));
		e.cdte_track = i;
		e.cdte_format = CDROM_LBA;
		if (ioctl(fd, CDROMREADTOCENTRY, &e) < 0) {
			free(v);
			return NULL;
		}
		v[k++] = i;
		v[k++] = e.cdte_addr.lba;
		v[k++] = e.cdte_ctrl;
	}
	memset(&e, 0, sizeof(e));
	e.cdte_track = CDROM_LEADOUT;
	e.cdte_format = CDROM_LBA;
	if (ioctl(fd, CDROMREADTOCENTRY, &e) < 0) {
		free(v);
		return NULL;
	}
	v[k++] = e.cdte_addr.lba;
	out = (*env)->NewIntArray(env, k);
	if (out)
		(*env)->SetIntArrayRegion(env, out, 0, k, v);
	free(v);
	return out;
}

/*
 * Reads `frames` audio sectors from `lba` into `buf` (2352 bytes each: 16-bit little-endian stereo PCM at
 * 44.1 kHz). Returns the number of bytes read, or -errno.
 */
JNIEXPORT jint JNICALL JNI_FN(readAudio)(JNIEnv *env, jclass cls, jint fd, jint lba, jint frames, jbyteArray buf)
{
	(void)cls;
	struct cdrom_read_audio ra;
	jsize len = (*env)->GetArrayLength(env, buf);
	jbyte *data;
	int r;

	if (frames <= 0 || (jlong)frames * CD_FRAMESIZE_RAW > len)
		return -EINVAL;
	data = (*env)->GetByteArrayElements(env, buf, NULL);
	if (!data)
		return -ENOMEM;
	memset(&ra, 0, sizeof(ra));
	ra.addr.lba = lba;
	ra.addr_format = CDROM_LBA;
	ra.nframes = frames;
	ra.buf = (__u8 *)data;
	r = ioctl(fd, CDROMREADAUDIO, &ra);
	if (r < 0) {
		r = -errno;
		(*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
		return r;
	}
	(*env)->ReleaseByteArrayElements(env, buf, data, 0);
	return frames * CD_FRAMESIZE_RAW;
}

/* Size of the disc's data area in bytes (BLKGETSIZE64), or -errno. */
JNIEXPORT jlong JNICALL JNI_FN(capacity)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	uint64_t size = 0;

	if (ioctl(fd, BLKGETSIZE64, &size) < 0)
		return -errno;
	return (jlong)size;
}

JNIEXPORT jint JNICALL JNI_FN(eject)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	ioctl(fd, CDROM_LOCKDOOR, 0);
	return ioctl(fd, CDROMEJECT, 0) < 0 ? -errno : 0;
}

JNIEXPORT jint JNICALL JNI_FN(closeTray)(JNIEnv *env, jclass cls, jint fd)
{
	(void)env;
	(void)cls;
	return ioctl(fd, CDROMCLOSETRAY, 0) < 0 ? -errno : 0;
}
