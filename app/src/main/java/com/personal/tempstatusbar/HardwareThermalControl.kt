    fun loadQuickTileIcon(context: Context, exactFileName: String): Icon? {
        try {
            val candidates = listOf(exactFileName, "Icons/$exactFileName", "icons/$exactFileName")
            var raw: Bitmap? = null
            for (path in candidates) {
                try {
                    context.assets.open(path).use { stream ->
                        raw = BitmapFactory.decodeStream(stream)
                    }
                    if (raw != null) break
                } catch (e: Exception) {}
            }
            val src = raw ?: return null
            val w = src.width
            val h = src.height
            if (w < 8 || h < 8) return null

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val c0 = pixels[0]
            val bgR = Color.red(c0)
            val bgG = Color.green(c0)
            val bgB = Color.blue(c0)
            val bgLum = (bgR + bgG + bgB) / 3

            for (i in pixels.indices) {
                val px = pixels[i]
                val a = Color.alpha(px)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val lum = (r + g + b) / 3

                val colorDist = (abs(r - bgR) + abs(g - bgG) + abs(b - bgB)) / 3
                val lumDist = abs(lum - bgLum)

                if (a < 40 || colorDist <= 45 || lumDist <= 38) {
                    pixels[i] = Color.TRANSPARENT
                } else {
                    val alphaOut = (lumDist * 2.5f).toInt().coerceIn(140, 255)
                    pixels[i] = Color.argb(alphaOut, 255, 255, 255)
                }
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return Icon.createWithBitmap(out)
        } catch (e: Exception) {
            return null
        }
    }
