# PurrfectBytes iOS App

Native SwiftUI iOS application for PurrfectBytes - Text to Speech and Video Generation platform.

## 📱 Two iOS Options Created

### 1. Flutter iOS App (Cross-Platform)
Located in: `purrfect_bytes_mobile/`
- **Benefits**: Share code with Android, faster development
- **Technology**: Flutter with iOS-specific configurations
- **File**: `purrfect_bytes_mobile/ios/`

### 2. Native Swift iOS App
Located in: `PurrfectBytes_iOS/`
- **Benefits**: Full iOS integration, better performance, native UI/UX
- **Technology**: SwiftUI + UIKit with native iOS features
- **File**: `PurrfectBytes_iOS/PurrfectBytes/`

## 🚀 Features

### Core Features
- **Text-to-Speech**: Local AVSpeechSynthesizer + Server API
- **Video Generation**: Custom video creation with text overlay
- **User Authentication**: JWT with Keychain secure storage
- **History Tracking**: View and manage past conversions
- **Cross-Device Sync**: Seamless integration with web app

### iOS-Specific Features
- **Native Audio Session**: Optimized for iOS audio handling
- **Background Audio**: Continues TTS in background
- **Share Extension**: Share content to other apps
- **Haptic Feedback**: Device vibration on actions
- **Dynamic Type**: Accessibility support for font sizes
- **Dark Mode**: Automatic light/dark theme support

## 📂 Native iOS Architecture

```
PurrfectBytes_iOS/
├── PurrfectBytesApp.swift          # App entry point
├── Views/
│   ├── ContentView.swift           # Main view controller
│   ├── LoginView.swift             # Authentication UI
│   ├── HomeView.swift              # TTS interface
│   ├── VideoCreationView.swift     # Video generation UI
│   ├── HistoryView.swift           # User history
│   └── SettingsView.swift          # App settings
├── Services/
│   ├── APIService.swift            # Backend communication
│   └── AuthenticationManager.swift # Authentication & security
├── ViewModels/
│   └── TTSViewModel.swift          # Text-to-speech logic
└── Resources/
    └── Info.plist                  # App permissions & config
```

## 🛠️ Setup Instructions

### Option 1: Flutter iOS (Recommended for Cross-Platform)

```bash
# Navigate to Flutter project
cd purrfect_bytes_mobile

# Install dependencies
flutter pub get

# Run on iOS simulator/device
flutter run -d ios
```

### Option 2: Native Swift iOS

1. **Open in Xcode**:
   ```bash
   open PurrfectBytes_iOS/PurrfectBytes.xcodeproj
   ```

2. **Configure Bundle Identifier**:
   - Set to `com.purrfectbytes.app`
   - Update team/signing certificates

3. **Update Server URL**:
   ```swift
   // In APIService.swift
   private let baseURL = "https://your-server.com" // Change from localhost
   ```

4. **Build & Run**:
   - Select target device/simulator
   - Press ⌘+R to build and run

## 🔧 iOS Configuration

### Permissions (Info.plist)
```xml
<!-- Microphone for audio recording -->
<key>NSMicrophoneUsageDescription</key>
<string>Record audio for transcription</string>

<!-- Camera for video features -->
<key>NSCameraUsageDescription</key>
<string>Record video with text overlay</string>

<!-- Photo library for saving videos -->
<key>NSPhotoLibraryAddUsageDescription</key>
<string>Save generated videos</string>
```

### Background Modes
- Audio playback
- Background processing
- Remote notifications

## 📱 iOS-Specific Features

### Audio Session Management
```swift
// Configured for optimal TTS performance
AVAudioSession.setCategory(.playAndRecord, options: [.defaultToSpeaker])
```

### Keychain Security
```swift
// Secure JWT token storage
KeychainService.storeToken(token)
```

### Native Sharing
```swift
// iOS share sheet integration
UIActivityViewController(activityItems: [content])
```

## 🔄 Backend Integration

Your FastAPI backend needs these additional endpoints for mobile:

```python
@app.post("/auth/login")
async def mobile_login(email: str, password: str):
    # Return JWT token + user info
    
@app.post("/auth/register") 
async def mobile_register(email: str, password: str, name: str):
    # Create user, return JWT token

@app.get("/user/history")
async def get_user_history(user = Depends(get_current_user)):
    # Return user's conversion history
```

## 📱 iOS Build & Distribution

### Development Build
```bash
# Flutter
flutter build ios --debug

# Native Swift
# Use Xcode: Product → Build
```

### App Store Release
```bash
# Flutter
flutter build ios --release
flutter build ipa

# Native Swift  
# Use Xcode: Product → Archive → Distribute App
```

## 🎯 Next Steps

1. **Backend Updates**: Add authentication endpoints to your FastAPI app
2. **Server Deployment**: Deploy backend to cloud service (AWS, Google Cloud)
3. **Push Notifications**: Add APNs for processing updates  
4. **App Store**: Submit to Apple App Store Connect
5. **TestFlight**: Beta testing with users

## 🔒 Security Features

- JWT tokens stored in iOS Keychain
- Certificate pinning for API calls
- Biometric authentication (Face ID/Touch ID) ready
- App Transport Security enabled
- Background app refresh controls

Both iOS options provide the same core functionality as your web app while leveraging native iOS capabilities for the best user experience!