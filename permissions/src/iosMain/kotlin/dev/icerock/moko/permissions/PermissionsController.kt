/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.moko.permissions

import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorized
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusDenied
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIApplication
import platform.UIKit.registeredForRemoteNotifications
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import platform.UserNotifications.*

actual class PermissionsController {
    private val locationManagerDelegate = LocationManagerDelegate()

    actual suspend fun providePermission(permission: Permission) {
        when (permission) {
            Permission.GALLERY -> provideGalleryPermission()
            Permission.CAMERA -> provideCameraPermission()
            Permission.STORAGE -> {
            } // not needed any permissions to storage
            Permission.LOCATION -> provideLocationPermission(permission)
            Permission.COARSE_LOCATION -> provideLocationPermission(permission)
            Permission.BLUETOOTH_LE -> {
            } // not needed any permissions to bt
            Permission.REMOTE_NOTIFICATION -> provideRemoteNotificationPermission()

        }
    }

    actual fun isPermissionGranted(permission: Permission): Boolean {
        return when (permission) {
            Permission.CAMERA -> AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized
            Permission.GALLERY -> PHPhotoLibrary.authorizationStatus() == PHAuthorizationStatusAuthorized
            Permission.STORAGE -> true
            Permission.LOCATION,
            Permission.COARSE_LOCATION -> {
                return listOf(
                    kCLAuthorizationStatusAuthorized,
                    kCLAuthorizationStatusAuthorizedAlways,
                    kCLAuthorizationStatusAuthorizedWhenInUse
                ).contains(CLLocationManager.authorizationStatus())
            }
            Permission.BLUETOOTH_LE -> true
            Permission.REMOTE_NOTIFICATION -> UIApplication.sharedApplication().registeredForRemoteNotifications
        }
    }

    private suspend fun provideRemoteNotificationPermission() {

        val currentCenter = UNUserNotificationCenter.currentNotificationCenter()

        val status = suspendCoroutine<UNAuthorizationStatus> { continuation ->
            currentCenter.getNotificationSettingsWithCompletionHandler(
                mainContinuation { settings: UNNotificationSettings? ->
                    continuation.resumeWith(Result.success(settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined))
                } )
        }
        when (status) {
            UNAuthorizationStatusAuthorized -> return
            UNAuthorizationStatusNotDetermined -> {
                val isSuccess = suspendCoroutine<Boolean> { continuation ->
                    UNUserNotificationCenter.currentNotificationCenter()
                        .requestAuthorizationWithOptions(
                            UNAuthorizationOptionSound.or(UNAuthorizationOptionAlert).or(
                                UNAuthorizationOptionBadge
                            ), mainContinuation { isOk, error ->
                                if (isOk == true && error == null) {
                                    continuation.resumeWith(Result.success(true))
                                } else {
                                    continuation.resumeWith(Result.success(false))
                                }
                            })
                }
                if (isSuccess) {
                    provideRemoteNotificationPermission()
                } else {
                    throw IllegalStateException("notifications permission failed")
                }
            }
            UNAuthorizationStatusDenied -> throw DeniedAlwaysException(Permission.REMOTE_NOTIFICATION)
            else -> throw IllegalStateException("notifications permission status $status")
        }
    }

    private suspend fun provideGalleryPermission(initialStatus: PHAuthorizationStatus? = null) {
        val status = initialStatus ?: PHPhotoLibrary.authorizationStatus()
        when (status) {
            PHAuthorizationStatusAuthorized -> return
            PHAuthorizationStatusNotDetermined -> {
                val newStatus = suspendCoroutine<PHAuthorizationStatus> { continuation ->
                    requestGalleryAccess { continuation.resume(it) }
                }
                provideGalleryPermission(newStatus)
            }
            PHAuthorizationStatusDenied -> throw DeniedAlwaysException(Permission.GALLERY)
            else -> throw IllegalStateException("gallery status $status")
        }
    }

    private suspend fun provideCameraPermission(initialStatus: AVAuthorizationStatus? = null) {
        val status =
            initialStatus ?: AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
        when (status) {
            AVAuthorizationStatusAuthorized -> return
            AVAuthorizationStatusNotDetermined -> {
                suspendCoroutine<Unit> { continuation ->
                    requestCameraAccess { continuation.resume(Unit) }
                }
                provideCameraPermission()
            }
            AVAuthorizationStatusDenied -> throw DeniedAlwaysException(Permission.CAMERA)
            else -> throw IllegalStateException("camera status $status")
        }
    }

    private suspend fun provideLocationPermission(
        permission: Permission,
        initialStatus: CLAuthorizationStatus? = null
    ) {
        val status = initialStatus ?: CLLocationManager.authorizationStatus()
        when (status) {
            kCLAuthorizationStatusAuthorized,
            kCLAuthorizationStatusAuthorizedAlways,
            kCLAuthorizationStatusAuthorizedWhenInUse -> return
            kCLAuthorizationStatusNotDetermined -> {
                val newStatus = suspendCoroutine<CLAuthorizationStatus> { continuation ->
                    locationManagerDelegate.requestLocationAccess { continuation.resume(it) }
                }
                provideLocationPermission(permission, newStatus)
            }
            kCLAuthorizationStatusDenied -> throw DeniedAlwaysException(permission)
            else -> throw IllegalStateException("location permission was denied")
        }
    }
}

private fun requestGalleryAccess(callback: (PHAuthorizationStatus) -> Unit) {
    PHPhotoLibrary.requestAuthorization(mainContinuation { status: PHAuthorizationStatus ->
        callback(status)
    })
}

private fun requestCameraAccess(callback: () -> Unit) {
    AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo, mainContinuation { _: Boolean ->
        callback()
    })
}

internal class LocationManagerDelegate : NSObject(), CLLocationManagerDelegateProtocol {
    private var callback: ((CLAuthorizationStatus) -> Unit)? = null

    private val locationManager = CLLocationManager()

    init {
        locationManager.delegate = this
    }

    fun requestLocationAccess(callback: (CLAuthorizationStatus) -> Unit) {
        this.callback = callback

        locationManager.requestWhenInUseAuthorization()
    }

    override fun locationManager(
        manager: CLLocationManager,
        didChangeAuthorizationStatus: CLAuthorizationStatus
    ) {
        callback?.invoke(didChangeAuthorizationStatus)
        callback = null
    }
}
