import SwiftUI

@main
struct PurrfectBytesApp: App {
    @StateObject private var authManager = AuthenticationManager()
    @StateObject private var apiService = APIService()
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(authManager)
                .environmentObject(apiService)
                .onAppear {
                    setupApp()
                }
        }
    }
    
    private func setupApp() {
        // Configure app settings
        configureAudioSession()
        
        // Load stored authentication
        authManager.loadStoredAuthentication()
    }
    
    private func configureAudioSession() {
        // Audio session configuration is handled in AppDelegate for more complex setups
    }
}