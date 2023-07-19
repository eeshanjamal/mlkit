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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import com.google.mlkit.md.R
import com.google.mlkit.md.Utils
import com.google.mlkit.md.settings.PreferenceUtils
import com.google.mlkit.md.utils.OrientationLiveData
import com.google.mlkit.md.utils.computeExifOrientation
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Manages the camera and allows UI updates on top of it (e.g. overlaying extra Graphics). This
 * receives preview frames from the camera at a specified rate, sends those frames to detector as
 * fast as it is able to process.
 *
 *
 * This camera source makes a best effort to manage processing on preview frames as fast as
 * possible, while at the same time minimizing lag. As such, frames may be dropped if the detector
 * is unable to keep up with the rate of frames generated by the camera.
 */

//TODO: Remove this interface once start using coroutine suspend functions
interface CameraCreateCallback{
    fun onSuccess(cameraDevice: CameraDevice)
    fun onFailure(error: Exception?)
}

//TODO: Remove this interface once start using coroutine suspend functions
interface CameraSessionCreateCallback{
    fun onSuccess(cameraCaptureSession: CameraCaptureSession)
    fun onFailure(error: Exception?)
}

class Camera2Source(private val graphicOverlay: GraphicOverlay) {

    private val context: Context = graphicOverlay.context

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** [CameraId] corresponding to the provided Camera facing back property */
    private val cameraId: String by lazy {
        cameraManager.cameraIdList.forEach {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CAMERA_FACING_BACK){
                return@lazy it
            }
        }
        throw IOException("No Camera found matching the back facing lens $CAMERA_FACING_BACK")
    }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    /** The [CameraDevice] that will be opened in this fragment */
    private var camera: CameraDevice? = null

    /** Readers used as buffers for camera still shots */
    private var imageReader: ImageReader? = null

    /** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
    private var session: CameraCaptureSession? = null

    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    /** [HandlerThread] where all buffer reading operations run */
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

    /** [Handler] corresponding to [imageReaderThread] */
    private val imageReaderHandler = Handler(imageReaderThread.looper)

    /** Live data property for retrieving the current device orientation relative to the camera or listening to the changes in it */
    private val relativeOrientation: OrientationLiveData by lazy {
        OrientationLiveData(context, characteristics)
    }

    /** Observer for listening the changes in the [relativeOrientation] live data property */
    private val orientationObserver  = androidx.lifecycle.Observer<Int> { rotation ->
        // Compute EXIF orientation metadata
        //val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        exifOrientation = computeExifOrientation(rotation, false)
    }

    /** Return the current exif orientation for processing image */
    private var exifOrientation:Int = 0

    /** Returns the preview size that is currently in use by the underlying camera.  */
    internal var previewSize: Size? = null
        private set

    /**
     * Dedicated thread and associated runnable for calling into the detector with frames, as the
     * frames become available from the camera.
     */
    private var processingThread: Thread? = null
    private val processingRunnable = FrameProcessingRunnable()

    private val processorLock = Object()
    private var frameProcessor: Frame2Processor? = null

    /**
     * Map to convert between a byte array, received from the camera, and its associated byte buffer.
     * We use byte buffers internally because this is a more efficient way to call into native code
     * later (avoids a potential copy).
     *
     *
     * **Note:** uses IdentityHashMap here instead of HashMap because the behavior of an array's
     * equals, hashCode and toString methods is both useless and unexpected. IdentityHashMap enforces
     * identity ('==') check on the keys.
     */
    private val bytesToByteBuffer = IdentityHashMap<ByteArray, ByteBuffer>()

    /**
     * Opens the camera and starts sending preview frames to the underlying detector. The supplied
     * surface holder is used for the preview so frames can be displayed to the user.
     *
     * @param surfaceHolder the surface holder to use for the preview frames.
     * @throws Exception if the supplied surface holder could not be used as the preview display.
     */
    @Synchronized
    @Throws(Exception::class)
    internal fun start(surfaceHolder: SurfaceHolder) {
        if (camera != null) return

        createCamera(object : CameraCreateCallback{
            override fun onSuccess(cameraDevice: CameraDevice) {
                camera      = cameraDevice
                previewSize = getPreviewAndPictureSize(characteristics).preview.also { previewSize ->
                    imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, IMAGE_FORMAT, IMAGE_BUFFER_SIZE).also {imageReader ->
                        createCaptureSession(cameraDevice, listOf(surfaceHolder.surface, imageReader.surface), object : CameraSessionCreateCallback{
                                override fun onSuccess(cameraCaptureSession: CameraCaptureSession) {
                                    session = cameraCaptureSession
                                    startPreview(cameraDevice, surfaceHolder, imageReader, cameraCaptureSession)
                                    relativeOrientation.observeForever(orientationObserver)
                                }

                                override fun onFailure(error: Exception?) {
                                    TODO("Not yet implemented")
                                }
                            }, cameraHandler)
                    }


                }
            }

            override fun onFailure(error: Exception?) {
                TODO("Not yet implemented")
            }

        })

        processingThread = Thread(processingRunnable).apply {
            processingRunnable.setActive(true)
            start()
        }
    }

    /**
     * Start the camera preview on the provided surface and process images through image reader buffer
     *
     * @param cameraDevice the camera device to select a preview from.
     * @param surfaceHolder the surface holder to use for the preview frames.
     * @param imageReader the image reader for receiving the preview images for processing.
     * @param session the configured camera capture session for the camera device.
     * @throws Exception if the supplied surface holder could not be used as the preview display.
     */

    @Throws(Exception::class)
    private fun startPreview(cameraDevice: CameraDevice, surfaceHolder: SurfaceHolder, imageReader: ImageReader, session: CameraCaptureSession){
        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceHolder.surface)
            // This will keep sending the capture request as frequently as possible until the
            // session is torn down or session.stopRepeating() is called
            session.setRepeatingRequest(build(), null, cameraHandler)

            //Setup listener for receiving the preview frames for processing
            imageReader.setOnImageAvailableListener({
                it.acquireNextImage()?.let {image ->
                    processingRunnable.setNextFrame(image, exifOrientation)
                }
            }, imageReaderHandler)

            relativeOrientation.observeForever{rotation ->

            }
        }
    }

    /**
     * Closes the camera and stops sending frames to the underlying frame detector.
     *
     *
     * This camera source may be restarted again by calling [.start].
     *
     *
     * Call [.release] instead to completely shut down this camera source and release the
     * resources of the underlying detector.
     */
    @Synchronized
    internal fun stop() {
        processingRunnable.setActive(false)
        processingThread?.let {
            try {
                // Waits for the thread to complete to ensure that we can't have multiple threads executing
                // at the same time (i.e., which would happen if we called start too quickly after stop).
                it.join()
            } catch (e: InterruptedException) {
                Log.e(TAG, "Frame processing thread interrupted on stop.")
            }
            processingThread = null
        }

        camera?.let {
            it.stopPreview()
            it.setPreviewCallbackWithBuffer(null)
            try {
                it.setPreviewDisplay(null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear camera preview: $e")
            }
            it.release()
            camera = null
        }

        // Release the reference to any image buffers, since these will no longer be in use.
        bytesToByteBuffer.clear()
    }

    /** Stops the camera and releases the resources of the camera and underlying detector.  */
    fun release() {
        graphicOverlay.clear()
        synchronized(processorLock) {
            stop()
            frameProcessor?.stop()
        }
    }

    fun setFrameProcessor(processor: Frame2Processor) {
        graphicOverlay.clear()
        synchronized(processorLock) {
            frameProcessor?.stop()
            frameProcessor = processor
        }
    }

    fun updateFlashMode(flashMode: String) {
        val parameters = camera?.parameters
        parameters?.flashMode = flashMode
        camera?.parameters = parameters
    }

    /**
     * Opens the camera and applies the user settings.
     *
     * @throws Exception if camera cannot be found or preview cannot be processed.
     */
    @Throws(Exception::class)
    private fun createCamera(callback: CameraCreateCallback) {

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("Camera permission not granted")
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                callback.onSuccess(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                callback.onFailure(null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = IOException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                callback.onFailure(exc)
            }

        }, cameraHandler)

    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session as callback [CameraSessionCreateCallback]
     *
     * @throws Exception if session cannot be created.
     */
    @Throws(Exception::class)
    private fun createCaptureSession(device: CameraDevice, targets: List<Surface>, callback: CameraSessionCreateCallback, handler: Handler? = null){
        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = callback.onSuccess(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                callback.onFailure(exc)
            }
        }, handler)
    }

    /**
     * Get the most suitable [CameraSizePair] from aspect ratio perspective.
     *
     * @throws Exception if cannot find a suitable size.
     */
    @Throws(Exception::class)
    private fun getPreviewAndPictureSize(characteristics: CameraCharacteristics): CameraSizePair {

        // Gives priority to the preview size specified by the user if exists.
        val sizePair: CameraSizePair = PreferenceUtils.getUserSpecifiedPreviewSize(context) ?: run {
            // Camera preview size is based on the landscape mode, so we need to also use the aspect
            // ration of display in the same mode for comparison.
            val displayAspectRatioInLandscape: Float =
                if (Utils.isPortraitMode(graphicOverlay.context)) {
                    graphicOverlay.height.toFloat() / graphicOverlay.width
                } else {
                    graphicOverlay.width.toFloat() / graphicOverlay.height
                }
            selectSizePair(characteristics, displayAspectRatioInLandscape)
        } ?: throw IOException("Could not find suitable preview size.")

        sizePair.preview.let {
            Log.v(TAG, "Camera preview size: $it")
            //parameters.setPreviewSize(it.width, it.height)
            PreferenceUtils.saveStringPreference(context, R.string.pref_key_rear_camera_preview_size, it.toString())
        }

        sizePair.picture?.let { pictureSize ->
            Log.v(TAG, "Camera picture size: $pictureSize")
            //parameters.setPictureSize(pictureSize.width, pictureSize.height)
            PreferenceUtils.saveStringPreference(
                context, R.string.pref_key_rear_camera_picture_size, pictureSize.toString()
            )
        }
        return sizePair
    }

    /**
     * Creates one buffer for the camera preview callback. The size of the buffer is based off of the
     * camera preview size and the format of the camera image.
     *
     * @return a new preview buffer of the appropriate size for the current camera settings.
     */
    private fun createPreviewBuffer(previewSize: Size): ByteArray {
        val bitsPerPixel = ImageFormat.getBitsPerPixel(IMAGE_FORMAT)
        val sizeInBits = previewSize.height.toLong() * previewSize.width.toLong() * bitsPerPixel.toLong()
        val bufferSize = ceil(sizeInBits / 8.0).toInt() + 1

        // Creating the byte array this way and wrapping it, as opposed to using .allocate(),
        // should guarantee that there will be an array to work with.
        val byteArray = ByteArray(bufferSize)
        val byteBuffer = ByteBuffer.wrap(byteArray)
        check(!(!byteBuffer.hasArray() || !byteBuffer.array()!!.contentEquals(byteArray))) {
            // This should never happen. If it does, then we wouldn't be passing the preview content to
            // the underlying detector later.
            "Failed to create valid buffer for camera source."
        }

        bytesToByteBuffer[byteArray] = byteBuffer
        return byteArray
    }

    /**
     * This runnable controls access to the underlying receiver, calling it to process frames when
     * available from the camera. This is designed to run detection on frames as fast as possible
     * (i.e., without unnecessary context switching or waiting on the next frame).
     *
     *
     * While detection is running on a frame, new frames may be received from the camera. As these
     * frames come in, the most recent frame is held onto as pending. As soon as detection and its
     * associated processing is done for the previous frame, detection on the mostly recently received
     * frame will immediately start on the same thread.
     */
    private inner class FrameProcessingRunnable internal constructor() : Runnable {

        // This lock guards all of the member variables below.
        private val lock = Object()
        private var active = true

        // These pending variables hold the state associated with the new frame awaiting processing.
        private var pendingFrame: Image? = null
        private var pendingFrameRotation: Int = 0

        /** Marks the runnable as active/not active. Signals any blocked threads to continue.  */
        internal fun setActive(active: Boolean) {
            synchronized(lock) {
                this.active = active
                lock.notifyAll()
            }
        }

        /**
         * Sets the frame data received from the camera. This adds the previous unused frame buffer (if
         * present) back to the camera, and keeps a pending reference to the frame data for future use.
         */
        internal fun setNextFrame(image: Image, rotation: Int) {
            synchronized(lock) {
                pendingFrame?.let {
                    it.close()
                    pendingFrame = null
                }

                pendingFrame = image
                pendingFrameRotation = rotation

                // Notify the processor thread if it is waiting on the next frame (see below).
                lock.notifyAll()
            }
        }

        /**
         * As long as the processing thread is active, this executes detection on frames continuously.
         * The next pending frame is either immediately available or hasn't been received yet. Once it
         * is available, we transfer the frame info to local variables and run detection on that frame.
         * It immediately loops back for the next frame without pausing.
         *
         *
         * If detection takes longer than the time in between new frames from the camera, this will
         * mean that this loop will run without ever waiting on a frame, avoiding any context switching
         * or frame acquisition time latency.
         *
         *
         * If you find that this is using more CPU than you'd like, you should probably decrease the
         * FPS setting above to allow for some idle time in between frames.
         */
        override fun run() {
            var data: Image?

            while (true) {
                synchronized(lock) {
                    while (active && pendingFrame == null) {
                        try {
                            // Wait for the next frame to be received from the camera, since we don't have it yet.
                            lock.wait()
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "Frame processing loop terminated.", e)
                            return
                        }
                    }

                    if (!active) {
                        // Exit the loop once this camera source is stopped or released.  We check this here,
                        // immediately after the wait() above, to handle the case where setActive(false) had
                        // been called, triggering the termination of this loop.
                        return
                    }

                    // Hold onto the frame data locally, so that we can use this for detection
                    // below.  We need to clear pendingFrameData to ensure that this buffer isn't
                    // recycled back to the camera before we are done using that data.
                    data = pendingFrame
                    pendingFrame = null
                }

                try {
                    synchronized(processorLock) {
                        data?.let {
                            frameProcessor?.process(it, pendingFrameRotation, graphicOverlay)
                        }
                    }
                } catch (t: Exception) {
                    Log.e(TAG, "Exception thrown from receiver.", t)
                } finally {
                    data?.close()
                }
            }
        }
    }

    companion object {

        const val CAMERA_FACING_BACK = CameraCharacteristics.LENS_FACING_BACK
        const val IMAGE_FORMAT = ImageFormat.YUV_420_888

        private const val TAG = "CameraSource"

        /** Maximum number of images that will be held in the reader's buffer */
        private const val IMAGE_BUFFER_SIZE: Int = 4

        private const val MIN_CAMERA_PREVIEW_WIDTH = 400
        private const val MAX_CAMERA_PREVIEW_WIDTH = 1300
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH = 640
        private const val DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT = 360
        private const val REQUESTED_CAMERA_FPS = 30.0f

        /**
         * Selects the most suitable preview and picture size, given the display aspect ratio in landscape
         * mode.
         *
         *
         * It's firstly trying to pick the one that has closest aspect ratio to display view with its
         * width be in the specified range [[.MIN_CAMERA_PREVIEW_WIDTH], [ ][.MAX_CAMERA_PREVIEW_WIDTH]]. If there're multiple candidates, choose the one having longest
         * width.
         *
         *
         * If the above looking up failed, chooses the one that has the minimum sum of the differences
         * between the desired values and the actual values for width and height.
         *
         *
         * Even though we only need to find the preview size, it's necessary to find both the preview
         * size and the picture size of the camera together, because these need to have the same aspect
         * ratio. On some hardware, if you would only set the preview size, you will get a distorted
         * image.
         *
         * @param camera the camera to select a preview size from
         * @return the selected preview and picture size pair
         */
        private fun selectSizePair(characteristics: CameraCharacteristics, displayAspectRatioInLandscape: Float): CameraSizePair? {
            val validPreviewSizes = Utils.generateValidPreviewSizeList(characteristics)

            var selectedPair: CameraSizePair? = null
            // Picks the preview size that has closest aspect ratio to display view.
            var minAspectRatioDiff = Float.MAX_VALUE

            for (sizePair in validPreviewSizes) {
                val previewSize = sizePair.preview
                if (previewSize.width < MIN_CAMERA_PREVIEW_WIDTH || previewSize.width > MAX_CAMERA_PREVIEW_WIDTH) {
                    continue
                }

                val previewAspectRatio = previewSize.width.toFloat() / previewSize.height.toFloat()
                val aspectRatioDiff = abs(displayAspectRatioInLandscape - previewAspectRatio)
                if (abs(aspectRatioDiff - minAspectRatioDiff) < Utils.ASPECT_RATIO_TOLERANCE) {
                    if (selectedPair == null || selectedPair.preview.width < sizePair.preview.width) {
                        selectedPair = sizePair
                    }
                } else if (aspectRatioDiff < minAspectRatioDiff) {
                    minAspectRatioDiff = aspectRatioDiff
                    selectedPair = sizePair
                }
            }

            if (selectedPair == null) {
                // Picks the one that has the minimum sum of the differences between the desired values and
                // the actual values for width and height.
                var minDiff = Integer.MAX_VALUE
                for (sizePair in validPreviewSizes) {
                    val size = sizePair.preview
                    val diff =
                        abs(size.width - DEFAULT_REQUESTED_CAMERA_PREVIEW_WIDTH) +
                                abs(size.height - DEFAULT_REQUESTED_CAMERA_PREVIEW_HEIGHT)
                    if (diff < minDiff) {
                        selectedPair = sizePair
                        minDiff = diff
                    }
                }
            }

            return selectedPair
        }
    }
}