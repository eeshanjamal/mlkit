/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.md.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import com.google.android.gms.common.images.Size
import com.google.mlkit.md.R
import com.google.mlkit.md.Utils
import java.io.IOException

/** Preview the camera image in the screen.  */
class Camera2SourcePreview(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {

    private val textureView: TextureView = TextureView(context).apply {
        surfaceTextureListener = TextureListener()
        addView(this)
    }
    private var graphicOverlay: GraphicOverlay? = null
    private var startRequested = false
    private var cameraSource: Camera2Source? = null
    private var cameraPreviewSize: Size? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        graphicOverlay = findViewById(R.id.camera_preview_graphic_overlay)
    }

    @Throws(IOException::class)
    fun start(cameraSource: Camera2Source) {
        this.cameraSource = cameraSource
        startRequested = true
        startIfReady()
    }

    fun stop() {
        cameraSource?.let {
            it.stop()
            cameraSource = null
            startRequested = false
        }
    }

    @Throws(IOException::class)
    private fun startIfReady() {
        if (startRequested && textureView.isAvailable) {
            cameraSource?.start(textureView)
            requestLayout()
            graphicOverlay?.let { overlay ->
                cameraSource?.let {
                    overlay.setCameraInfo(it)
                }
                overlay.clear()
            }
            startRequested = false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val layoutWidth = right - left
        val layoutHeight = bottom - top

        cameraSource?.previewSize?.let { cameraPreviewSize = it }

        val previewSizeRatio = cameraPreviewSize?.let { size ->
            if (Utils.isPortraitMode(context)) {
                // Camera's natural orientation is landscape, so need to swap width and height.
                size.height.toFloat() / size.width
            } else {
                size.width.toFloat() / size.height
            }
        } ?: (layoutWidth.toFloat() / layoutHeight.toFloat())

        // Match the width of the child view to its parent.
        val childHeight = (layoutWidth / previewSizeRatio).toInt()
        if (childHeight <= layoutHeight) {
            for (i in 0 until childCount) {
                getChildAt(i).layout(0, 0, layoutWidth, childHeight)
            }
        } else {
            // When the child view is too tall to be fitted in its parent: If the child view is
            // static overlay view container (contains views such as bottom prompt chip), we apply
            // the size of the parent view to it. Otherwise, we offset the top/bottom position
            // equally to position it in the center of the parent.
            val excessLenInHalf = (childHeight - layoutHeight) / 2
            for (i in 0 until childCount) {
                val childView = getChildAt(i)
                when (childView.id) {
                    R.id.static_overlay_container -> {
                        childView.layout(0, 0, layoutWidth, layoutHeight)
                    }
                    else -> {
                        childView.layout(
                            0, -excessLenInHalf, layoutWidth, layoutHeight + excessLenInHalf
                        )
                    }
                }
            }
        }

        try {
            startIfReady()
        } catch (e: IOException) {
            Log.e(TAG, "Could not start camera source.", e)
        }
    }

    private inner class TextureListener : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            try {
                startIfReady()
            } catch (e: IOException) {
                Log.e(TAG, "Could not start camera source.", e)
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    companion object {
        private const val TAG = "CameraSourcePreview"
    }
}
