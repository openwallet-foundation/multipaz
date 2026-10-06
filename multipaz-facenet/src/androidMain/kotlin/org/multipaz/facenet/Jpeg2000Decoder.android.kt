package org.multipaz.facenet

import android.graphics.Bitmap
import jj2000.j2k.codestream.HeaderInfo
import jj2000.j2k.codestream.reader.BitstreamReaderAgent
import jj2000.j2k.codestream.reader.HeaderDecoder
import jj2000.j2k.decoder.Decoder
import jj2000.j2k.fileformat.reader.FileFormatReader
import jj2000.j2k.image.BlkImgDataSrc
import jj2000.j2k.image.DataBlkInt
import jj2000.j2k.image.ImgDataConverter
import jj2000.j2k.image.invcomptransf.InvCompTransf
import jj2000.j2k.io.RandomAccessIO
import jj2000.j2k.util.ISRandomAccessIO
import jj2000.j2k.util.ParameterList
import jj2000.j2k.wavelet.synthesis.InverseWT
import java.io.ByteArrayInputStream

internal fun decodePortraitBytesToBitmap(bytes: ByteArray): Bitmap? {
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (bitmap != null) {
        return bitmap
    }
    return decodeJpeg2000ToBitmap(bytes)
}

internal fun decodeJpeg2000ToBitmap(bytes: ByteArray): Bitmap? {
    return try {
        val defPl = ParameterList()
        val pinfo = Decoder.getAllParameters()
        if (pinfo != null) {
            for (i in pinfo.indices.reversed()) {
                if (pinfo[i][3] != null) {
                    defPl.put(pinfo[i][0], pinfo[i][3])
                }
            }
        }
        val pl = ParameterList(defPl)
        pl.setProperty("u", "on")
        val isr = ISRandomAccessIO(ByteArrayInputStream(bytes))
        val ffr = FileFormatReader(isr)
        ffr.readFileFormat()
        val stream: RandomAccessIO = if (ffr.JP2FFUsed) {
            ISRandomAccessIO(ByteArrayInputStream(bytes, ffr.firstCodeStreamPos, ffr.firstCodeStreamLength))
        } else {
            isr
        }

        val hi = HeaderInfo()
        val hd = HeaderDecoder(stream, pl, hi)
        val decSpec = hd.decoderSpecs
        val nComp = hd.numComps
        val breader = BitstreamReaderAgent.createInstance(stream, hd, pl, decSpec, false, hi)
        val depth = IntArray(nComp) { i -> hd.getOriginalBitDepth(i) }

        val entDec = hd.createEntropyDecoder(breader, pl)
        val roiDecoder = hd.createROIDeScaler(entDec, pl, decSpec)
        val dequant = hd.createDequantizer(roiDecoder, depth, decSpec)
        val invWT = InverseWT.createInstance(dequant, decSpec)
        invWT.setImgResLevel(breader.imgRes)

        val converter = ImgDataConverter(invWT, 0)
        val icomp = InvCompTransf(converter, decSpec, depth, pl)
        icomp.setTile(0, 0)

        var src: BlkImgDataSrc = icomp
        if (ffr.JP2FFUsed && "off" != pl.getParameter("nocolorspace")) {
            val csMap = colorspace.ColorSpace(isr, hd, pl)
            src = hd.createChannelDefinitionMapper(src, csMap)
            src = hd.createResampler(src, csMap)
            src = hd.createPalettizedColorSpaceMapper(src, csMap)
            src = hd.createColorSpaceMapper(src, csMap)
        }

        val width = src.imgWidth
        val height = src.imgHeight

        val fpR = src.getFixedPoint(0)
        val fpG = if (nComp >= 3) src.getFixedPoint(1) else fpR
        val fpB = if (nComp >= 3) src.getFixedPoint(2) else fpR

        val rShift = if (!hd.isOriginalSigned(0)) 1 shl (hd.getOriginalBitDepth(0) - 1) else 0
        val gShift = if (nComp >= 3 && !hd.isOriginalSigned(1)) 1 shl (hd.getOriginalBitDepth(1) - 1) else rShift
        val bShift = if (nComp >= 3 && !hd.isOriginalSigned(2)) 1 shl (hd.getOriginalBitDepth(2) - 1) else rShift

        val dataBlkR = DataBlkInt()
        val dataBlkG = DataBlkInt()
        val dataBlkB = DataBlkInt()

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            dataBlkR.ulx = 0
            dataBlkR.uly = y
            dataBlkR.w = width
            dataBlkR.h = 1

            val blkR = src.getInternCompData(dataBlkR, 0) as DataBlkInt
            val rData = blkR.data
            val rOff = blkR.offset

            val blkG: DataBlkInt
            val gData: IntArray
            val gOff: Int

            val blkB: DataBlkInt
            val bData: IntArray
            val bOff: Int

            if (nComp >= 3) {
                dataBlkG.ulx = 0
                dataBlkG.uly = y
                dataBlkG.w = width
                dataBlkG.h = 1
                blkG = src.getInternCompData(dataBlkG, 1) as DataBlkInt
                gData = blkG.data
                gOff = blkG.offset

                dataBlkB.ulx = 0
                dataBlkB.uly = y
                dataBlkB.w = width
                dataBlkB.h = 1
                blkB = src.getInternCompData(dataBlkB, 2) as DataBlkInt
                bData = blkB.data
                bOff = blkB.offset
            } else {
                blkG = blkR
                gData = rData
                gOff = rOff

                blkB = blkR
                bData = rData
                bOff = rOff
            }

            val outRow = y * width
            for (x in 0 until width) {
                val rVal = (rData[rOff + x] shr fpR) + rShift
                val gVal = (gData[gOff + x] shr fpG) + gShift
                val bVal = (bData[bOff + x] shr fpB) + bShift
                val r = rVal.coerceIn(0, 255)
                val g = gVal.coerceIn(0, 255)
                val b = bVal.coerceIn(0, 255)
                pixels[outRow + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        null
    }
}
