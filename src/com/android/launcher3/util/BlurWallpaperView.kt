/*
 * Copyright (C) 2023 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.util

import android.content.Context
import android.content.ContentResolver
import android.provider.Settings
import android.graphics.Shader
import android.graphics.RenderEffect
import android.graphics.drawable.Drawable
import android.app.WallpaperManager
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageView

class BlurWallpaperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ImageView(context, attrs) {

    init {
        Log.d("BlurWallpaperView", "Initializing BlurWallpaperView")
        try {
            initializeBlurWallpaper()
        } catch (e: Exception) {
            Log.e("BlurWallpaperView", "Error during initialization", e)
        }
    }

    private fun initializeBlurWallpaper() {
        // Log statements to debug the process
        Log.d("BlurWallpaperView", "Starting initialization")

        // Get blur radius from system settings
        val contentResolver: ContentResolver = context.contentResolver
        val blurRadius = Settings.System.getInt(contentResolver, "WP_BLUR_RADIUS", 12)

        Log.d("BlurWallpaperView", "Blur radius: $blurRadius")

        // Apply blur effect to the ImageView if blurRadius is not zero
        if (blurRadius != 0) {
            val blurRadiusFloat = blurRadius.toFloat()
            val tileMode = Shader.TileMode.CLAMP
            val renderEffect = RenderEffect.createBlurEffect(blurRadiusFloat, blurRadiusFloat, tileMode)
            setRenderEffect(renderEffect)
            Log.d("BlurWallpaperView", "RenderEffect applied")
        }

        // Set wallpaper drawable to the ImageView
        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperDrawable: Drawable? = wallpaperManager.drawable
        wallpaperDrawable?.let {
            setImageDrawable(it)
            Log.d("BlurWallpaperView", "Wallpaper drawable set")
        } ?: run {
            setImageDrawable(null)
            Log.w("BlurWallpaperView", "Wallpaper drawable is null")
        }
    }
}
