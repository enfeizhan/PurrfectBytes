package com.purrfectbytes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.purrfectbytes.android.ui.screens.CameraScreen
import com.purrfectbytes.android.ui.screens.MainScreen
import com.purrfectbytes.android.ui.theme.PurrfectBytesTheme
import com.purrfectbytes.android.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PurrfectBytesTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val showCamera by viewModel.showCamera.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // The camera is only opened once its permission is granted (see MainScreen)
                    if (showCamera) {
                        // Back leaves the camera, not the app
                        BackHandler { viewModel.closeCamera() }

                        CameraScreen(
                            createPhotoFile = viewModel::newPhotoFile,
                            onPhotoCapture = { uri ->
                                viewModel.onPhotoCaptured(uri)
                            },
                            onBack = {
                                viewModel.closeCamera()
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
