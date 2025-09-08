# PurrfectBytes Android (Native)

Native Android application for PurrfectBytes Text-to-Speech and Video Generation platform, built with Kotlin and Jetpack Compose.

## 🚀 Features

### Core Functionality
- **Text-to-Speech**: Local Android TTS engine + Server API
- **Video Generation**: Create videos with text overlay
- **User Authentication**: Secure login with JWT tokens
- **History Tracking**: View past conversions
- **Material Design 3**: Modern Android UI with dynamic theming

### Android-Specific Features
- **Jetpack Compose UI**: Declarative UI with modern Android toolkit
- **Hilt Dependency Injection**: Clean architecture with DI
- **Coroutines & Flow**: Reactive programming with Kotlin
- **ExoPlayer**: Professional media playback
- **DataStore**: Modern preferences storage
- **Background Service**: Audio playback with notifications

## 📁 Project Structure

```
PurrfectBytes_Android/
├── app/
│   ├── src/main/
│   │   ├── java/com/purrfectbytes/android/
│   │   │   ├── MainActivity.kt              # Main entry point
│   │   │   ├── PurrfectBytesApplication.kt  # Application class
│   │   │   ├── ui/
│   │   │   │   ├── screens/                # Compose screens
│   │   │   │   ├── components/             # Reusable composables
│   │   │   │   └── theme/                  # Material 3 theming
│   │   │   ├── viewmodels/                 # ViewModels (MVVM)
│   │   │   ├── data/
│   │   │   │   ├── api/                    # Retrofit API
│   │   │   │   ├── repository/             # Data repositories
│   │   │   │   └── models/                 # Data models
│   │   │   ├── di/                         # Hilt modules
│   │   │   ├── navigation/                 # Navigation graph
│   │   │   └── services/                   # Android services
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts                    # App build config
├── gradle/
└── build.gradle.kts                        # Project build config
```

## 🛠️ Technology Stack

- **Language**: Kotlin 1.9+
- **UI Framework**: Jetpack Compose
- **Architecture**: MVVM with Clean Architecture
- **Dependency Injection**: Hilt
- **Networking**: Retrofit + OkHttp
- **Async**: Coroutines + Flow
- **Media**: ExoPlayer
- **Local Storage**: DataStore, Room (optional)
- **Image Loading**: Coil

## 📱 Requirements

- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Kotlin**: 1.9.22
- **Gradle**: 8.2
- **Android Studio**: Hedgehog or newer

## 🚀 Setup & Build

### Prerequisites
1. Install Android Studio
2. Install Android SDK (API 24-34)
3. Configure local.properties with SDK path

### Build Instructions

```bash
# Clone the repository
cd PurrfectBytes_Android

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run all tests
./gradlew test

# Build release APK
./gradlew assembleRelease
```

### Configuration

1. **API Base URL**: Update in `AppModule.kt`
```kotlin
.baseUrl("https://your-api-server.com/")
```

2. **Signing Config**: Add to `app/build.gradle.kts` for release builds
```kotlin
signingConfigs {
    create("release") {
        // Add your signing configuration
    }
}
```

## 🎨 UI/UX Features

### Material Design 3
- Dynamic color theming (Android 12+)
- Material You components
- Dark/Light theme support
- Edge-to-edge display

### Screens
1. **Login/Register**: Secure authentication
2. **Home**: Text-to-speech conversion
3. **Video Creation**: Generate videos with options
4. **History**: View past conversions
5. **Settings**: App preferences

### Compose Components
- Custom TextField with character counter
- Language selector dropdown
- Playback controls
- Loading states with Shimmer effect
- Error handling with Snackbars

## 🔧 Architecture

### MVVM Pattern
```kotlin
View (Compose) → ViewModel → Repository → API/Database
```

### Clean Architecture Layers
1. **Presentation**: UI (Compose) + ViewModels
2. **Domain**: Use cases + Business logic
3. **Data**: Repository + API + Local storage

### Dependency Injection
- Hilt for compile-time DI
- Scoped components (Singleton, ViewModelScoped)
- Module-based provision

## 📦 Dependencies

```kotlin
dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    
    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.48")
    
    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    
    // Media
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    
    // See build.gradle.kts for complete list
}
```

## 🧪 Testing

### Unit Tests
```bash
./gradlew test
```

### Instrumented Tests
```bash
./gradlew connectedAndroidTest
```

### Test Coverage
```bash
./gradlew createDebugCoverageReport
```

## 🚀 Deployment

### Google Play Store

1. **Generate Signed Bundle**
```bash
./gradlew bundleRelease
```

2. **Upload to Play Console**
- app/build/outputs/bundle/release/app-release.aab

3. **Required Assets**
- App icon: 512x512 PNG
- Feature graphic: 1024x500
- Screenshots: Various device sizes

## 🔐 Security

- ProGuard/R8 obfuscation enabled
- Certificate pinning ready
- Encrypted preferences with EncryptedSharedPreferences
- Network security config
- No cleartext traffic (except localhost for dev)

## 📊 Performance

- Lazy loading with Compose
- Image caching with Coil
- Efficient list rendering with LazyColumn
- Background work with WorkManager
- Memory leak detection with LeakCanary (debug)

## 🎯 Comparison with iOS

| Feature | Android (Native) | iOS (Native) |
|---------|-----------------|--------------|
| Language | Kotlin | Swift |
| UI Framework | Jetpack Compose | SwiftUI |
| Architecture | MVVM + Hilt | MVVM |
| Networking | Retrofit | URLSession |
| Local TTS | Android TTS | AVSpeechSynthesizer |
| Min Version | Android 7.0 | iOS 12.0 |

## 🚀 Next Steps

1. **Complete UI Implementation**: Add remaining screens
2. **Offline Support**: Implement Room database
3. **Push Notifications**: Firebase Cloud Messaging
4. **Analytics**: Firebase Analytics
5. **Crashlytics**: Error reporting
6. **CI/CD**: GitHub Actions for automated builds

---

**Note**: This is the native Android implementation. For the iOS native app, see `../PurrfectBytes_iOS/`