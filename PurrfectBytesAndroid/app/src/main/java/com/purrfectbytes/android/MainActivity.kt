package com.purrfectbytes.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.purrfectbytes.android.ui.screens.CameraScreen
import com.purrfectbytes.android.ui.screens.MainScreen
import com.purrfectbytes.android.ui.theme.PurrfectBytesTheme
import com.purrfectbytes.android.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@OptIn(ExperimentalPermissionsApi::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PurrfectBytesTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val showCamera by viewModel.showCamera.collectAsState()

                val cameraPermissionState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.CAMERA
                    )
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showCamera) {
                        if (cameraPermissionState.allPermissionsGranted) {
                            CameraScreen(
                                onPhotoCapture = { uri ->
                                    viewModel.onPhotoCaptured(uri)
                                },
                                onBack = {
                                    viewModel.closeCamera()
                                }
                            )
                        } else {
                            // Request camera permission
                            cameraPermissionState.launchMultiplePermissionRequest()
                            viewModel.closeCamera()
                        }
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}