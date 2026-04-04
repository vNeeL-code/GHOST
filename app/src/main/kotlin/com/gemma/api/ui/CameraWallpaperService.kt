package com.gemma.api.ui

import android.hardware.Camera
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import timber.log.Timber

@Suppress("DEPRECATION")
class CameraWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return CameraEngine()
    }

    inner class CameraEngine : Engine() {
        private var camera: Camera? = null

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                startCamera()
            } else {
                stopCamera()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startCamera()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            stopCamera()
        }

        private fun startCamera() {
            if (camera != null) return
            try {
                camera = Camera.open()
                camera?.setDisplayOrientation(90) // Portrait orientation
                
                // Set continuous auto-focus if supported
                val params = camera?.parameters
                if (params?.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    camera?.parameters = params
                }

                camera?.setPreviewDisplay(surfaceHolder)
                camera?.startPreview()
            } catch (e: Exception) {
                Timber.e(e, "Wallpaper Camera failed to open")
                stopCamera()
            }
        }

        private fun stopCamera() {
            try {
                camera?.stopPreview()
                camera?.release()
                camera = null
            } catch (e: Exception) {
                Timber.e(e, "Wallpaper Camera failed to release")
            }
        }
    }
}
