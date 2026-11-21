package com.angelmilanov.awsdbconnector

import com.angelmilanov.awsdbconnector.ui.AppIcon
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Build helper: writes the application icon in the format jpackage needs on each operating system
 * (args: output directory). Without these files the launcher, shortcuts and installers get the default Java icon.
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    File(out, "AWSDBConnector.ico").writeBytes(ico(listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { AppIcon.app(it) }))
    File(out, "AWSDBConnector.icns").writeBytes(icns())
    File(out, "AWSDBConnector.png").writeBytes(png(AppIcon.app(512)))
}

private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

/** Windows icon file: a directory followed by the images, as PNG at 256 pixels and as bitmaps below that. */
private fun ico(images: List<BufferedImage>): ByteArray {
    val data = images.map { if (it.width >= 256) png(it) else icoBitmap(it) }
    val buf = ByteBuffer.allocate(6 + 16 * images.size + data.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0).putShort(1).putShort(images.size.toShort())
    var offset = 6 + 16 * images.size
    images.forEachIndexed { i, image ->
        buf.put((image.width and 0xFF).toByte()).put((image.height and 0xFF).toByte()) // 256 is stored as 0
        buf.put(0).put(0).putShort(1).putShort(32).putInt(data[i].size).putInt(offset)
        offset += data[i].size
    }
    data.forEach(buf::put)
    return buf.array()
}

/** 32 bit bitmap as stored in an icon: rows bottom-up, followed by a 1 bit transparency mask. */
private fun icoBitmap(image: BufferedImage): ByteArray {
    val w = image.width
    val h = image.height
    val maskStride = (w + 31) / 32 * 4
    val buf = ByteBuffer.allocate(40 + w * h * 4 + maskStride * h).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(40).putInt(w).putInt(h * 2).putShort(1).putShort(32).putInt(0).putInt(w * h * 4 + maskStride * h)
    buf.putInt(0).putInt(0).putInt(0).putInt(0)
    for (y in h - 1 downTo 0) for (x in 0 until w) buf.putInt(image.getRGB(x, y)) // ARGB, little endian = B G R A
    for (y in h - 1 downTo 0) {
        val row = ByteArray(maskStride)
        for (x in 0 until w) if (image.getRGB(x, y) ushr 24 == 0) row[x / 8] = (row[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
        buf.put(row)
    }
    return buf.array()
}

/** macOS icon file: PNG images tagged with the slot they fill ("ic11" = 16 points on a Retina screen, ...). */
private fun icns(): ByteArray {
    val slots = listOf("ic11" to 32, "ic12" to 64, "ic07" to 128, "ic13" to 256, "ic08" to 256, "ic14" to 512, "ic09" to 512, "ic10" to 1024)
    // macOS icons are drawn on a canvas that leaves about 10 % free on every side.
    val images = slots.map { (_, size) -> size }.distinct().associateWith { png(AppIcon.app(it, margin = 100f / 1024f)) }
    val buf = ByteBuffer.allocate(8 + slots.sumOf { (_, size) -> 8 + images.getValue(size).size })
    buf.put("icns".toByteArray(Charsets.US_ASCII)).putInt(buf.capacity())
    for ((type, size) in slots) {
        val data = images.getValue(size)
        buf.put(type.toByteArray(Charsets.US_ASCII)).putInt(8 + data.size).put(data)
    }
    return buf.array()
}
