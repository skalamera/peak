package com.cl600.obd.ui

import android.content.Context
import android.graphics.Typeface

object FontCache {
    @Volatile
    private var boldItalic: Typeface? = null

    @Volatile
    private var semiBoldItalic: Typeface? = null

    fun racingBoldItalic(context: Context): Typeface {
        return boldItalic ?: synchronized(this) {
            boldItalic ?: loadAsset(context, "fonts/ChakraPetch-BoldItalic.ttf", Typeface.BOLD_ITALIC)
                .also { boldItalic = it }
        }
    }

    fun racingSemiBoldItalic(context: Context): Typeface {
        return semiBoldItalic ?: synchronized(this) {
            semiBoldItalic ?: loadAsset(context, "fonts/ChakraPetch-SemiBoldItalic.ttf", Typeface.ITALIC)
                .also { semiBoldItalic = it }
        }
    }

    private fun loadAsset(context: Context, assetPath: String, fallbackStyle: Int): Typeface {
        return runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, assetPath)
        }.getOrElse {
            Typeface.create(Typeface.SANS_SERIF, fallbackStyle)
        }
    }
}
