import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var authManager: AuthenticationManager
    @AppStorage("useLocalTTS") private var useLocalTTS = true
    @AppStorage("defaultLanguage") private var defaultLanguage = "en"
    @AppStorage("defaultSlowMode") private var defaultSlowMode = false
    @AppStorage("serverURL") private var serverURL = "http://localhost:8000"
    @State private var showingLogoutAlert = false
    @State private var showingAbout = false
    
    var body: some View {
        NavigationView {
            List {
                // User Section
                Section {
                    if let user = authManager.currentUser,
                       let name = user["name"] as? String,
                       let email = user["email"] as? String {
                        HStack {
                            Image(systemName: "person.circle.fill")
                                .font(.system(size: 40))
                                .foregroundColor(.blue)
                            
                            VStack(alignment: .leading) {
                                Text(name)
                                    .font(.headline)
                                Text(email)
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                } header: {
                    Text("Account")
                }
                
                // TTS Settings
                Section {
                    Toggle("Use Local TTS", isOn: $useLocalTTS)
                    
                    HStack {
                        Text("Default Language")
                        Spacer()
                        Picker("Language", selection: $defaultLanguage) {
                            Text("English").tag("en")
                            Text("Spanish").tag("es")
                            Text("French").tag("fr")
                            Text("German").tag("de")
                            Text("Italian").tag("it")
                        }
                        .pickerStyle(MenuPickerStyle())
                    }
                    
                    Toggle("Default Slow Mode", isOn: $defaultSlowMode)
                } header: {
                    Text("TTS Settings")
                } footer: {
                    Text("Local TTS works offline but server TTS offers more voices and features.")
                }
                
                // Server Settings
                Section {
                    HStack {
                        Text("Server URL")
                        Spacer()
                        TextField("Server URL", text: $serverURL)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .frame(width: 200)
                    }
                    
                    Button("Test Connection") {
                        // Test server connection
                    }
                    .foregroundColor(.blue)
                } header: {
                    Text("Server Settings")
                } footer: {
                    Text("Change this to your production server URL when deploying.")
                }
                
                // App Settings
                Section {
                    NavigationLink("Privacy Policy") {
                        PrivacyPolicyView()
                    }
                    
                    NavigationLink("Terms of Service") {
                        TermsOfServiceView()
                    }
                    
                    Button("About") {
                        showingAbout = true
                    }
                    .foregroundColor(.primary)
                } header: {
                    Text("App Info")
                }
                
                // Account Actions
                Section {
                    Button("Sign Out") {
                        showingLogoutAlert = true
                    }
                    .foregroundColor(.red)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .alert("Sign Out", isPresented: $showingLogoutAlert) {
                Button("Cancel", role: .cancel) { }
                Button("Sign Out", role: .destructive) {
                    authManager.logout()
                }
            } message: {
                Text("Are you sure you want to sign out?")
            }
            .sheet(isPresented: $showingAbout) {
                AboutView()
            }
        }
    }
}

struct AboutView: View {
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Image(systemName: "cat.fill")
                    .font(.system(size: 80))
                    .foregroundColor(.blue)
                
                Text("PurrfectBytes")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                
                Text("Version 1.0.0")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                Text("Transform your text into beautiful speech and videos with ease.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .padding()
                
                VStack(alignment: .leading, spacing: 8) {
                    Text("Features:")
                        .font(.headline)
                    
                    Label("Text to Speech conversion", systemImage: "speaker.wave.2")
                    Label("Custom video generation", systemImage: "video")
                    Label("Multiple language support", systemImage: "globe")
                    Label("Cross-platform sync", systemImage: "icloud")
                }
                .padding()
                
                Spacer()
                
                Text("Made with ❤️ for PurrfectBytes")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
            .padding()
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarItems(trailing: Button("Done") {
                // Dismiss modal
            })
        }
    }
}

struct PrivacyPolicyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Privacy Policy")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                
                Text("Last updated: \(Date(), formatter: DateFormatter.shortDate)")
                    .foregroundColor(.secondary)
                
                Group {
                    Text("Data Collection")
                        .font(.headline)
                    
                    Text("We collect only the information necessary to provide our text-to-speech and video generation services. This includes:")
                    
                    Text("• Text content you submit for processing\n• Account information (email, name)\n• Usage statistics to improve our service")
                    
                    Text("Data Usage")
                        .font(.headline)
                    
                    Text("Your data is used solely to provide and improve our services. We do not sell or share your personal information with third parties.")
                    
                    Text("Data Storage")
                        .font(.headline)
                    
                    Text("Generated audio and video files are temporarily stored on our servers and automatically deleted after 24 hours.")
                }
                
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Privacy Policy")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct TermsOfServiceView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Terms of Service")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                
                Text("Last updated: \(Date(), formatter: DateFormatter.shortDate)")
                    .foregroundColor(.secondary)
                
                Group {
                    Text("Acceptable Use")
                        .font(.headline)
                    
                    Text("You agree to use PurrfectBytes only for lawful purposes and in accordance with these terms.")
                    
                    Text("Service Availability")
                        .font(.headline)
                    
                    Text("We strive to provide reliable service but cannot guarantee 100% uptime. The service is provided 'as is' without warranties.")
                    
                    Text("Account Responsibilities")
                        .font(.headline)
                    
                    Text("You are responsible for maintaining the confidentiality of your account credentials and for all activities under your account.")
                }
                
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Terms of Service")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension DateFormatter {
    static let shortDate: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter
    }()
}

#Preview {
    SettingsView()
        .environmentObject(AuthenticationManager())
}