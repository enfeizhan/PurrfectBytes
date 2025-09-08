import SwiftUI
import AVFoundation

struct HomeView: View {
    @StateObject private var viewModel = TTSViewModel()
    @EnvironmentObject var apiService: APIService
    @State private var inputText = ""
    @State private var selectedLanguage = "en"
    @State private var isSlowMode = false
    @State private var useLocalTTS = true
    @State private var isProcessing = false
    
    let languages = [
        ("en", "English"),
        ("es", "Spanish"),
        ("fr", "French"),
        ("de", "German"),
        ("it", "Italian"),
        ("pt", "Portuguese"),
        ("ru", "Russian"),
        ("ja", "Japanese"),
        ("ko", "Korean"),
        ("zh", "Chinese")
    ]
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Welcome Card
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Welcome to PurrfectBytes")
                            .font(.title2)
                            .fontWeight(.bold)
                        
                        Text("Convert text to speech with ease")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // Text Input
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Enter Text")
                            .font(.headline)
                        
                        TextEditor(text: $inputText)
                            .frame(minHeight: 150)
                            .padding(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color(.systemGray4), lineWidth: 1)
                            )
                    }
                    
                    // Settings
                    VStack(spacing: 16) {
                        // TTS Mode Toggle
                        Toggle("Use Local TTS", isOn: $useLocalTTS)
                            .padding(.horizontal)
                        
                        // Language Picker
                        HStack {
                            Text("Language")
                                .font(.headline)
                            
                            Spacer()
                            
                            Picker("Language", selection: $selectedLanguage) {
                                ForEach(languages, id: \.0) { code, name in
                                    Text(name).tag(code)
                                }
                            }
                            .pickerStyle(MenuPickerStyle())
                        }
                        .padding(.horizontal)
                        
                        // Slow Mode Toggle
                        Toggle("Slow Mode", isOn: $isSlowMode)
                            .padding(.horizontal)
                    }
                    .padding(.vertical)
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // Convert Button
                    Button(action: convertToSpeech) {
                        HStack {
                            if isProcessing {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                    .scaleEffect(0.8)
                            } else {
                                Image(systemName: "play.fill")
                            }
                            Text(isProcessing ? "Processing..." : "Convert to Speech")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(inputText.isEmpty || isProcessing)
                    
                    // Playback Controls
                    HStack(spacing: 20) {
                        Button(action: { viewModel.stopSpeaking() }) {
                            Label("Stop", systemImage: "stop.fill")
                        }
                        
                        Button(action: { viewModel.pauseSpeaking() }) {
                            Label("Pause", systemImage: "pause.fill")
                        }
                        
                        Button(action: { inputText = "" }) {
                            Label("Clear", systemImage: "xmark.circle.fill")
                        }
                    }
                    .font(.callout)
                }
                .padding()
            }
            .navigationTitle("Text to Speech")
            .navigationBarTitleDisplayMode(.large)
        }
    }
    
    private func convertToSpeech() {
        guard !inputText.isEmpty else { return }
        
        isProcessing = true
        
        if useLocalTTS {
            viewModel.speak(text: inputText, language: selectedLanguage, rate: isSlowMode ? 0.3 : 0.5)
            isProcessing = false
        } else {
            Task {
                do {
                    let request = TTSRequest(text: inputText, language: selectedLanguage, slow: isSlowMode)
                    let response = try await apiService.convertTextToSpeech(request: request)
                    
                    if let filename = response["filename"] as? String {
                        // Download and play audio
                        let audioFile = try await apiService.downloadAudio(filename: filename)
                        viewModel.playAudioFile(url: audioFile)
                    }
                } catch {
                    print("Error: \(error)")
                }
                
                await MainActor.run {
                    isProcessing = false
                }
            }
        }
    }
}

#Preview {
    HomeView()
        .environmentObject(APIService())
}