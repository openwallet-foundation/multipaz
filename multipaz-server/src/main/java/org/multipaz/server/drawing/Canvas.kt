package org.multipaz.server.drawing

import io.ktor.util.encodeBase64
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.cache
import org.multipaz.util.findFirstGood
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.collections.List
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Server-side drawing interface geared towards customizing card art for issued credentials.
 *
 * Drawing methods have `config` argument that provides drawing parameters in JSON format.
 * The following properties are common for all operations (`0` is default value for all of them):
 *  - `x` - horizontal position for the drawing
 *  - `y` - vertical position for the drawing
 *  - `rotate` - rotation in degrees clockwise around the drawing position
 *
 * Also [drawText] and [drawRect] take an array of `effects`, where each element of
 * the array is an object. The type of the effect is selected using `type` value. The
 * following types are supported (and their parameters):
 *  - `fill` - fills the object with an ARGB color
 *    - `argb` - 8-digit hex string that defines the color (white is default)
 *  - `blur` - fills the object with an ARGB color and blurs the result using Gaussian blur
 *    - `argb` - 8-digit hex string that defines the color (black is default)
 *    - `radius` - Gaussian blur radius (2 is default)
 *    - `dx` - additional horizontal shift (0 is default)
 *    - `dy` - additional vertical shift (0 is default)
 *  - `lighting` - draw the object using the given color; then using only object's
 *      opacity map as a starting point, blur it using given radius and use the result as
 *      an elevation map to calculate lighting using a single distant light source
 *    - `argb` - 8-digit hex string that defines the color ("FFC0C0C0" is default)
 *    - `light_rgb` - 6-digit hex string that defines light source RGB color (white is default)
 *    - `amplitude` - strength of the lighting effect (1 is default)
 *    - `surface_scale` - scale of the elevation map (1.5 is default)
 *    - `azimuth` - azimuth angle of the lighting source in degrees (140 is default)
 *    - `elevation` - elevation angle of the lighting source in degrees (30 is default)
 *    - `specular_exponent` - directional density of the lighting source (13 is default)
 *    - `blur_radius` - radius of the opacity map blurring
 *
 * Specific details on the lighting and blurring can be found in
 * [W3C SVG 1.1 Specification](https://www.w3.org/TR/SVG11/filters.html).
 */
class Canvas private constructor(val bufferedImage: BufferedImage) {
    private val graphics = bufferedImage.createGraphics()!!

    /** Width of the canvas in pixels. */
    val width: Int get() = bufferedImage.width

    /** Height of the canvas in pixels. */
    val height: Int get() = bufferedImage.height

    init {
        setHints(graphics)
    }

    /**
     * Draws the image, loading it using the resource name.
     *
     * Image is always drawn preserving the aspect ratio
     *
     * Following additional drawing parameters are supported:
     *  - `width` - width of the area where the image should be placed (canvas width by default)
     *  - `height` - height of the area where the image should be placed (canvas height by default)
     *  - `fill` - controls image scaling; if `true` (default) image should completely fill the
     *     drawing area, sides may be cropped if needed, if `false` image should be fully visible
     *     even if drawing area is not completely filled.
     *
     * @param name name of the resource file that contains an image
     * @param config image drawing parameters
     */
    suspend fun drawImage(name: String, config: JsonObject) {
        drawImage(loadImage(name), config)
    }

    /**
     * Draw the image encoded in the given [ByteString].
     *
     * @param bytes byte array that encodes the image in PNG or JPEG format
     * @param config image drawing parameters
     */
    fun drawImage(bytes: ByteString, config: JsonObject) {
        drawImage(loadImage(bytes), config)
    }

    private fun drawImage(image: BufferedImage, config: JsonObject) {
        val transform = config.getTransform()
        val width = config.getFloat("width", width.toFloat())
        val height = config.getFloat("height", height.toFloat())
        val scaleX = width / image.width
        val scaleY = height / image.height
        val scale = if (config.getBoolean("fill", true)) {
            max(scaleX, scaleY)
        } else {
            min(scaleX, scaleY)
        }
        transform.translate(0.5 * (width - scale * image.width), 0.5 * (height - scale * image.height))
        val g = graphics.create() as Graphics2D
        g.transform = transform
        g.clip = Rectangle2D.Float(0f, 0f, width, height)
        val imageTransform = AffineTransform.getScaleInstance(scale.toDouble(), scale.toDouble())
        g.drawImage(image, imageTransform, null)
        g.dispose()
    }

    /**
     * Draws the rectangle.
     *
     * Effects and the following additional drawing parameters are supported:
     *  - `width` - width of the rectangle (canvas width by default)
     *  - `height` - height of the rectangle (canvas height by default)
     *  - `radius` - when non-zero, radius of the rounding for the rectangle corners (0 by default)
     *
     * @param config parameters to draw the rectangle
     */
    fun drawRect(config: JsonObject) {
        val effects = config.getEffects("effects") ?: return
        val width = config.getFloat("width", width.toFloat())
        val height = config.getFloat("height", height.toFloat())
        val radius = config.getFloat("radius", 0.0f)
        val rect = if (radius <= 0f) {
            Rectangle2D.Float(0f, 0f, width, height)
        } else {
            RoundRectangle2D.Float(0f, 0f, width, height, radius, radius)
        }
        graphics.transform = config.getTransform()
        applyEffects(effects, rect) { g ->
            // NB: x, y passed into the closure here
            g.fill(rect)
        }
    }

    /**
     * Draws text.
     *
     * If text is wider than the limit given by `max_width` parameter, text is truncated and
     * ellipses added.
     *
     * Effects and the following additional drawing parameters are supported:
     *  - `font` - name of the font file from the resources or a list of names, when a list of
     *    names is given, each following font must be more condensed than the previous one. If text
     *    does not fit in the maximum width given, the next font is tried before truncating the
     *    text
     *  - `font_size` - height of the font (60 by default)
     *  - `max_width` - maximum text width (not limited by default)
     *
     * @param text text to draw
     * @param config parameters to draw the text
     */
    suspend fun drawText(
        text: String,
        config: JsonObject
    ) {
        val effects = config.getEffects("effects")
            ?: listOf(Blur(radius = 3f), Lighting(0xFFCCCC00U))
        val fontSize = config.getFloat("font_size", 60f)
        val maxWidth = config.getFloat("max_width", Float.POSITIVE_INFINITY)
        val fonts = when (val fontConfig = config["font"]) {
            is JsonArray -> fontConfig.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(fontConfig.content)
            else -> DEFAULT_FONTS
        }
        graphics.transform = AffineTransform()
        var (font, bounds) = selectFont(text, fontSize, maxWidth, fonts)
        var textToDraw = text
        if (bounds.width > maxWidth) {
            // Did not fit
            val ellipsis = "\u2026"
            val fm = graphics.getFontMetrics(font)
            // use advance for more precise
            val availableWidth = maxWidth - fm.stringWidth(ellipsis)
            val drop = findFirstGood(text.length) { drop ->
                fm.stringWidth(text.take(text.length - drop)) <= availableWidth
            }
            textToDraw = text.take(text.length - drop) + ellipsis
            bounds = fm.getStringBounds(textToDraw, graphics)
        }
        graphics.transform = config.getTransform()
        applyEffects(effects, bounds) { g ->
            g.font = font
            g.drawString(textToDraw, 0f, 0f)
        }
    }

    private suspend fun selectFont(
        text: String,
        fontSize: Float,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        fonts: List<String> = DEFAULT_FONTS
    ): Pair<Font, Rectangle2D> {
        for (fontName in fonts) {
            val scaledFont = loadFont(fontName).deriveFont(Font.PLAIN, fontSize)
            val bounds = graphics.getFontMetrics(scaledFont).getStringBounds(text, graphics)
            if (bounds.width < maxWidth || fontName == fonts.last()) {
                return Pair(scaledFont, bounds)
            }
        }
        // not reached
        throw IllegalStateException()
    }

    private fun applyEffects(
        effects: List<Effect>,
        shape: Shape,
        drawAction: (g: Graphics2D) -> Unit
    ) {
        val bounds = graphics.transform.createTransformedShape(shape).bounds
        for (effect in effects) {
            if (effect is Fill) {
                // just an optimization
                val g = graphics.create() as Graphics2D
                g.paint = toPaint(effect.argb)
                //g.clip = shape
                drawAction.invoke(g)
                g.dispose()
                continue
            }
            val inset = ceil(effect.inset).toInt() + (if (effect.offsetX == 0f) 0 else 1)
            val eWidth = bounds.width + 2 * inset
            val eHeight = bounds.height + 2 * inset
            var ex = bounds.x - inset + floor(effect.offsetX).toInt()
            var ey = bounds.y - inset + floor(effect.offsetY).toInt()
            val eTransform = AffineTransform()
            eTransform.translate(
                (effect.offsetX - ex).toDouble(),
                (effect.offsetY - ey).toDouble()
            )
            var dstX = 0
            var dstY = 0
            var dstW = eWidth
            var dstH = eHeight
            if (ex < 0) {
                dstW += ex
                dstX = -ex
                ex = 0
            }
            if (ey < 0) {
                dstH += ey
                dstY = -ey
                ey = 0
            }
            if (ex + dstW > width) {
                dstW = width - ex
            }
            if (ey + dstH > height) {
                dstH = height - ey
            }
            if (dstW > 0 && dstH > 0) {
                val eImage = BufferedImage(eWidth, eHeight, BufferedImage.TYPE_BYTE_GRAY)
                val eGraphics = eImage.createGraphics()
                setHints(eGraphics)
                eGraphics.paint = Color.WHITE
                eTransform.concatenate(graphics.transform)
                eGraphics.transform = eTransform
                drawAction.invoke(eGraphics)
                val src = ByteArray(eWidth * eHeight)
                val dst = IntArray(dstW * dstH)
                eImage.raster.getDataElements(0, 0, eWidth, eHeight, src)
                bufferedImage.raster.getDataElements(ex, ey, dstW, dstH, dst)
                effect.applyAlpha(src, eWidth, eHeight, dst, dstX, dstY, dstW, dstH)
                bufferedImage.raster.setDataElements(ex, ey, dstW, dstH, dst)
            }
        }
    }

    /**
     * Returns pixel array (as premultiplied ARGB colors packed in integers) that represents
     * the canvas content.
     */
    fun toPixels(): IntArray {
        val pixels = IntArray(width * height)
        bufferedImage.raster.getDataElements(0, 0, width, height, pixels)
        return pixels
    }

    /**
     * Returns PNG image that represents the canvas content.
     */
    fun toPng(): ByteArray {
        val stream = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "PNG", stream)
        return stream.toByteArray()
    }

    /**
     * Returns data url that represents the canvas content.
     */
    fun toDataUrl(): String {
        return "data:image/png;base64," + toPng().encodeBase64()
    }

    companion object {
        val DEFAULT_FONTS: List<String> = listOf(
            "fonts/OpenSans-Regular.ttf",
            "fonts/OpenSans_SemiCondensed-Regular.ttf",
            "fonts/OpenSans_Condensed-Regular.ttf"
        )

        /**
         * Creates a blank (fully transparent) canvas with the given dimensions.
         *
         * @param width width of the new canvas in pixels
         * @param height height of the new canvas in pixels
         * @return new blank [Canvas]
         */
        fun createBlank(width: Int, height: Int): Canvas {
            return Canvas(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE))
        }

        /**
         * Creates a [Canvas] that has the given image as a background; canvas dimensions
         * match the image.
         *
         * @param name name of the image which is loaded from the resources
         * @return new [Canvas]
         */
        suspend fun fromImage(name: String): Canvas {
            val image = loadImage(name)
            return if (image.type == BufferedImage.TYPE_INT_ARGB_PRE) {
                Canvas(image)
            } else {
                createBlank(image.width, image.height).apply {
                    drawImage(image, buildJsonObject {})
                }
            }
        }

        private suspend fun loadImage(name: String): BufferedImage =
            BackendEnvironment.cache(BufferedImage::class, name) { _, resources ->
                loadImage(resources.getRawResource(name)!!)
            }

        private suspend fun loadFont(name: String): Font =
            BackendEnvironment.cache(Font::class, name) { _, resources ->
                Font.createFont(
                    Font.TRUETYPE_FONT,
                    ByteArrayInputStream(resources.getRawResource(name)!!.toByteArray())
                )
            }

        private fun loadImage(bytes: ByteString): BufferedImage =
            ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))

        private fun toPaint(argb: UInt): Color {
            return Color(argb.toInt(), true)
        }

        private fun setHints(graphics: Graphics2D) {
            graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            )
            graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
            )
        }

        private fun JsonObject.getFloat(name: String, defaultValue: Float = 0f): Float =
            this[name]?.let { if (it is JsonPrimitive) it.floatOrNull else null }
                ?: defaultValue

        private fun JsonObject.getBoolean(name: String, defaultValue: Boolean = false): Boolean =
            this[name]?.let { it is JsonPrimitive && !it.isString && it.content == "true" }
                ?: defaultValue

        private fun JsonObject.getTransform(): AffineTransform {
            val x = getFloat("x", 0f)
            val y = getFloat("y", 0f)
            val transform = AffineTransform()
            transform.translate(x.toDouble(), y.toDouble())
            transform.rotate((Math.PI / 180.0) * getFloat("rotate", 0f))
            return transform
        }

        private fun JsonObject.getEffects(name: String): List<Effect>? =
            (this[name] as? JsonArray)?.map { element ->
                val effectConfig = element.jsonObject
                when (val type = effectConfig["type"]!!.jsonPrimitive.content) {
                    "fill" -> Fill(effectConfig.getArgb("argb"))
                    "lighting" -> Lighting(
                        argb = effectConfig.getArgb("argb", 0xFFC0C0C0U),
                        rgbLight = effectConfig.getRgb("light_rgb"),
                        amplitude = effectConfig.getFloat("amplitude", 1f),
                        surfaceScale = effectConfig.getFloat("surface_scale", 1.5f),
                        azimuth = effectConfig.getFloat("azimuth", 140f),
                        elevation = effectConfig.getFloat("elevation", 30f),
                        specularExponent = effectConfig.getFloat("specular_exponent", 13f),
                        elevationMapEffect = Blur(
                            radius = effectConfig.getFloat("blur_radius", 1f)
                        )
                    )
                    "blur" -> Blur(
                        argb = effectConfig.getArgb("argb", 0xFF000000U),
                        radius = effectConfig.getFloat("radius", 2.0f),
                        dx = effectConfig.getFloat("dx", 0f),
                        dy = effectConfig.getFloat("dy", 0f),
                    )
                    else -> throw IllegalArgumentException("Unknown effect: '$type'")
                }
            }


        private fun JsonObject.getArgb(name: String, defaultValue: UInt = 0xFFFFFFFFU): UInt =
            this[name]?.let {
                if (it is JsonPrimitive && it.isString) {
                    if (it.content.length == 6) {
                        it.content.hexToUInt() or 0xFF000000U
                    } else {
                        it.content.hexToUInt()
                    }
                } else {
                    defaultValue
                }
            } ?: defaultValue

        private fun JsonObject.getRgb(name: String, defaultValue: UInt = 0xFFFFFFFFU): UInt =
            this[name]?.let {
                if (it is JsonPrimitive && it.isString) {
                    it.content.hexToUInt() or 0xFF000000U
                } else {
                    defaultValue
                }
            } ?: defaultValue
    }
}