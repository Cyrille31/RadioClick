/*
 * RadioClic — CGExcel — Cyrille Gindre — © 2026 — MIT + BAL 1.0
 */
package com.cgexcel.radioclic.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cgexcel.radioclic.ShortcutActivity
import com.cgexcel.radioclic.model.Tile

/** Raccourcis épinglés sur l'écran d'accueil, lançant directement une tuile. */
object Shortcuts {

    private fun shortcutId(tileId: String) = "tile-$tileId"

    fun isSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Demande au lanceur d'épingler un raccourci vers la tuile. */
    suspend fun pin(context: Context, tile: Tile): Boolean {
        if (!isSupported(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, build(context, tile), null)
    }

    /** Met à jour le libellé et l'icône d'un raccourci déjà épinglé. */
    fun refresh(context: Context, tile: Tile) {
        val pinned = runCatching {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        }.getOrDefault(emptyList())
        if (pinned.none { it.id == shortcutId(tile.id) }) return
        runCatching {
            ShortcutManagerCompat.updateShortcuts(context, listOf(buildInfo(context, tile, letterIcon(tile))))
        }
    }

    /** Désactive les raccourcis des tuiles supprimées. */
    fun disable(context: Context, tileIds: List<String>) {
        if (tileIds.isEmpty()) return
        runCatching {
            ShortcutManagerCompat.disableShortcuts(
                context,
                tileIds.map(::shortcutId),
                "Cette tuile a été supprimée",
            )
        }
    }

    private suspend fun build(context: Context, tile: Tile): ShortcutInfoCompat {
        val image = tile.displayImage?.let { loadBitmap(context, it) }
        val icon = if (image != null) imageIcon(tile, image) else letterIcon(tile)
        return buildInfo(context, tile, icon)
    }

    private fun buildInfo(context: Context, tile: Tile, icon: Bitmap): ShortcutInfoCompat {
        val intent = Intent(context, ShortcutActivity::class.java)
            .setAction(ShortcutActivity.ACTION_PLAY_TILE)
            .putExtra(ShortcutActivity.EXTRA_TILE_ID, tile.id)
        val label = tile.title.ifBlank { "RadioClic" }
        return ShortcutInfoCompat.Builder(context, shortcutId(tile.id))
            .setShortLabel(label.take(24))
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
            .setIntent(intent)
            .build()
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(ICON_SIZE)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return result.drawable.toBitmap()
    }

    // Icône adaptative : 108 dp dont 72 dp visibles au centre.
    private const val ICON_SIZE = 432

    private fun background(tile: Tile): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tile.color.toInt())
        return bitmap to canvas
    }

    private fun imageIcon(tile: Tile, image: Bitmap): Bitmap {
        val (bitmap, canvas) = background(tile)
        val inset = ICON_SIZE / 6f
        val dest = RectF(inset, inset, ICON_SIZE - inset, ICON_SIZE - inset)
        canvas.drawBitmap(image, Rect(0, 0, image.width, image.height), dest, Paint(Paint.FILTER_BITMAP_FLAG))
        return bitmap
    }

    private fun letterIcon(tile: Tile): Bitmap {
        val (bitmap, canvas) = background(tile)
        val letter = tile.title.trim().firstOrNull()?.uppercase() ?: "♪"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLight(tile.color)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            textSize = ICON_SIZE * 0.4f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val y = ICON_SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, ICON_SIZE / 2f, y, paint)
        return bitmap
    }

    private fun isLight(color: Long): Boolean {
        val c = color.toInt()
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) > 160
    }
}
