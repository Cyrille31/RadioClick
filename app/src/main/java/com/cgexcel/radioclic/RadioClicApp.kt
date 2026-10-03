/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cgexcel.radioclic.net.Http

class RadioClicApp : Application(), ImageLoaderFactory {

    /** Chargeur d'images (pochettes, logos de radios) partageant le client HTTP. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(Http.client)
            .crossfade(true)
            .build()
}
