/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.cgexcel.radioclic.net.FeedResolver
import com.cgexcel.radioclic.net.Http

class RadioClicApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        FeedResolver.init(this)
    }

    /** Chargeur d'images (pochettes, logos de radios) partageant le client HTTP. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            // Beaucoup de logos de radios sont au format SVG (Wikimedia…).
            .components { add(SvgDecoder.Factory()) }
            .crossfade(true)
            .build()
}
