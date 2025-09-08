import SwiftUI
import AVKit

struct VideoCreationView: View {
    @EnvironmentObject var apiService: APIService
    @State private var inputText = ""
    @State private var selectedLanguage = "en"
    @State private var videoType = "simple"
    @State private var duration = 10
    @State private var backgroundColor = Color.black
    @State private var textColor = Color.white
    @State private var fontSize = 48
    @State private var isProcessing = false
    @State private var videoURL: URL?
    @State private var showingColorPicker = false
    @State private var isBackgroundColorPicker = true
    
    let videoTypes = ["simple", "animated", "gradient"]
    let languages = [
        ("en", "English"),
        ("es", "Spanish"),
        ("fr", "French"),
        ("de", "German"),
        ("it", "Italian")
    ]
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Video Player
                    if let videoURL = videoURL {
                        VideoPlayer(player: AVPlayer(url: videoURL))
                            .frame(height: 250)
                            .cornerRadius(12)
                    } else {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color(.systemGray5))
                            .frame(height: 250)
                            .overlay(
                                VStack {
                                    Image(systemName: "video.slash")
                                        .font(.system(size: 50))
                                        .foregroundColor(.gray)
                                    Text("No video loaded")
                                        .foregroundColor(.gray)
                                }
                            )
                    }
                    
                    // Text Input
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Video Text")
                            .font(.headline)
                        
                        TextEditor(text: $inputText)
                            .frame(minHeight: 100)
                            .padding(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color(.systemGray4), lineWidth: 1)
                            )
                    }
                    
                    // Settings
                    VStack(spacing: 16) {
                        // Video Type & Duration
                        HStack {
                            VStack(alignment: .leading) {
                                Text("Video Type")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                
                                Picker("Video Type", selection: $videoType) {
                                    ForEach(videoTypes, id: \.self) { type in
                                        Text(type.capitalized).tag(type)
                                    }
                                }
                                .pickerStyle(SegmentedPickerStyle())
                            }
                            
                            Spacer()
                            
                            VStack(alignment: .leading) {
                                Text("Duration")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                
                                Stepper("\(duration)s", value: $duration, in: 5...60, step: 5)
                            }
                        }
                        
                        // Colors
                        HStack(spacing: 20) {
                            VStack {
                                Text("Background")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                
                                Button(action: {
                                    isBackgroundColorPicker = true
                                    showingColorPicker = true
                                }) {
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(backgroundColor)
                                        .frame(height: 50)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .stroke(Color(.systemGray4), lineWidth: 1)
                                        )
                                }
                            }
                            
                            VStack {
                                Text("Text Color")
                                    .font(.subheadline)
                                    .foregroundColor(.secondary)
                                
                                Button(action: {
                                    isBackgroundColorPicker = false
                                    showingColorPicker = true
                                }) {
                                    RoundedRectangle(cornerRadius: 8)
                                        .fill(textColor)
                                        .frame(height: 50)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 8)
                                                .stroke(Color(.systemGray4), lineWidth: 1)
                                        )
                                }
                            }
                        }
                        
                        // Font Size
                        VStack(alignment: .leading) {
                            Text("Font Size: \(fontSize)")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            
                            Slider(value: Binding(
                                get: { Double(fontSize) },
                                set: { fontSize = Int($0) }
                            ), in: 12...72, step: 1)
                        }
                    }
                    .padding()
                    .background(Color(.systemGray6))
                    .cornerRadius(12)
                    
                    // Create Video Button
                    Button(action: createVideo) {
                        HStack {
                            if isProcessing {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                    .scaleEffect(0.8)
                            } else {
                                Image(systemName: "video.fill")
                            }
                            Text(isProcessing ? "Creating Video..." : "Create Video")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(inputText.isEmpty || isProcessing)
                }
                .padding()
            }
            .navigationTitle("Create Video")
            .sheet(isPresented: $showingColorPicker) {
                ColorPicker(
                    isBackgroundColorPicker ? "Background Color" : "Text Color",
                    selection: isBackgroundColorPicker ? $backgroundColor : $textColor
                )
                .padding()
                .presentationDetents([.medium])
            }
        }
    }
    
    private func createVideo() {
        guard !inputText.isEmpty else { return }
        
        isProcessing = true
        
        Task {
            do {
                let request = VideoRequest(
                    text: inputText,
                    language: selectedLanguage,
                    videoType: videoType,
                    duration: duration,
                    backgroundColor: backgroundColor.toHex(),
                    textColor: textColor.toHex(),
                    fontSize: fontSize
                )
                
                let response = try await apiService.createVideo(request: request)
                
                if let filename = response["filename"] as? String {
                    let downloadedURL = try await apiService.downloadVideo(filename: filename)
                    await MainActor.run {
                        videoURL = downloadedURL
                    }
                }
            } catch {
                print("Error creating video: \(error)")
            }
            
            await MainActor.run {
                isProcessing = false
            }
        }
    }
}

extension Color {
    func toHex() -> String {
        let uic = UIColor(self)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        
        uic.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        
        let rgb: Int = (Int)(red*255)<<16 | (Int)(green*255)<<8 | (Int)(blue*255)<<0
        
        return String(format:"#%06x", rgb)
    }
}

#Preview {
    VideoCreationView()
        .environmentObject(APIService())
}