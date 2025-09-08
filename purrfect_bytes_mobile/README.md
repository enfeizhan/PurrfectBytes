# PurrfectBytes Mobile App (Flutter)

A cross-platform mobile application for PurrfectBytes - Text to Speech and Video Generation platform, built with Flutter for both iOS and Android.

## 📱 Project Structure

```
purrfect_bytes_mobile/
├── lib/                    # Flutter/Dart code
│   ├── main.dart          # App entry point
│   ├── screens/           # UI screens
│   ├── services/          # API and platform services
│   ├── models/            # Data models
│   └── widgets/           # Reusable widgets
├── android/               # Android-specific code
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── kotlin/    # Native Android code
│   │   │   └── res/       # Android resources
│   │   └── build.gradle   # Android build config
│   └── AndroidManifest.xml
├── ios/                   # iOS-specific code
│   ├── Runner/
│   │   ├── AppDelegate.swift
│   │   └── Info.plist
│   └── Podfile
├── test/                  # Unit and widget tests
├── pubspec.yaml          # Flutter dependencies
├── README_MOBILE.md      # Mobile-specific docs
└── README_ANDROID.md     # Android-specific docs
```

## 🚀 Quick Start

### Prerequisites
- Flutter SDK (3.0+)
- Android Studio / Xcode
- iOS Simulator / Android Emulator or physical device

### Installation

```bash
# Clone the repository
cd purrfect_bytes_mobile

# Get Flutter dependencies
flutter pub get

# iOS-specific setup (macOS only)
cd ios && pod install && cd ..

# Run the app
flutter run  # Will prompt for device selection
```

### Platform-Specific Run

```bash
# Android
flutter run -d android

# iOS (macOS only)
flutter run -d ios

# List available devices
flutter devices
```

## 🎯 Features

### Core Features
- ✅ **Text-to-Speech**: Local and server-based TTS
- ✅ **Video Generation**: Create videos with text overlay
- ✅ **User Authentication**: JWT-based secure login
- ✅ **History Tracking**: View past conversions
- ✅ **Cross-Platform**: Single codebase for iOS & Android

### Platform Features
- **Android**: Background audio service, material design, dark theme
- **iOS**: Native audio session, iOS share sheet, haptic feedback
- **Offline Mode**: Local TTS works without internet
- **Permission Handling**: Smart permission requests

## 🔧 Configuration

### API Server
Update the server URL in `lib/services/api_service.dart`:
```dart
static const String baseUrl = 'http://localhost:8000'; // Change for production
```

### Build Variants
- **Debug**: Development build with debugging enabled
- **Release**: Optimized production build

## 📦 Build for Production

### Android APK
```bash
flutter build apk --release
# Output: build/app/outputs/flutter-apk/app-release.apk
```

### Android App Bundle (Play Store)
```bash
flutter build appbundle --release
# Output: build/app/outputs/bundle/release/app-release.aab
```

### iOS IPA
```bash
flutter build ios --release
# Then archive in Xcode for App Store
```

## 🧪 Testing

```bash
# Run all tests
flutter test

# Run with coverage
flutter test --coverage

# Run specific test file
flutter test test/widget_test.dart
```

## 📝 Important Notes

### Android
- Minimum SDK: 21 (Android 5.0)
- Target SDK: 34 (Android 14)
- See `README_ANDROID.md` for Android-specific details

### iOS
- Minimum iOS: 12.0
- Optimized for iPhone and iPad
- Requires Xcode for final build

## 🔒 Permissions

### Android Permissions
- RECORD_AUDIO
- CAMERA
- WRITE_EXTERNAL_STORAGE
- INTERNET
- VIBRATE

### iOS Permissions
- NSMicrophoneUsageDescription
- NSCameraUsageDescription
- NSPhotoLibraryAddUsageDescription

## 🛠️ Troubleshooting

### Common Issues

1. **Build fails on iOS**
   ```bash
   cd ios && pod install && cd ..
   flutter clean && flutter pub get
   ```

2. **Android Gradle issues**
   ```bash
   cd android && ./gradlew clean && cd ..
   flutter clean && flutter pub get
   ```

3. **API connection issues**
   - For Android emulator: Use `10.0.2.2` instead of `localhost`
   - For iOS simulator: `localhost` works
   - For physical devices: Use your computer's IP address

## 📚 Additional Resources

- [Flutter Documentation](https://flutter.dev/docs)
- [Android-specific README](README_ANDROID.md)
- [API Documentation](../README.md)

## 🚀 Next Steps

1. **Backend Setup**: Ensure your FastAPI backend is running
2. **Authentication**: Add JWT endpoints to your backend
3. **Testing**: Test on various devices and OS versions
4. **Deployment**: Submit to App Store and Play Store

---

**Note**: This is the Flutter mobile app. For the native iOS Swift app, see `../PurrfectBytes_iOS/`