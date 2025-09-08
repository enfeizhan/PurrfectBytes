import SwiftUI

struct HistoryView: View {
    @EnvironmentObject var apiService: APIService
    @State private var history: [[String: Any]] = []
    @State private var isLoading = true
    @State private var showingAlert = false
    @State private var alertMessage = ""
    
    var body: some View {
        NavigationView {
            Group {
                if isLoading {
                    ProgressView("Loading history...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if history.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "clock")
                            .font(.system(size: 64))
                            .foregroundColor(.gray)
                        
                        Text("No history yet")
                            .font(.title2)
                            .foregroundColor(.gray)
                        
                        Text("Your conversions will appear here")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                } else {
                    List {
                        ForEach(Array(history.enumerated()), id: \.offset) { index, item in
                            HistoryRowView(item: item)
                                .swipeActions(edge: .trailing) {
                                    Button("Delete", role: .destructive) {
                                        deleteItem(at: index)
                                    }
                                }
                        }
                    }
                    .refreshable {
                        await loadHistory()
                    }
                }
            }
            .navigationTitle("History")
            .navigationBarTitleDisplayMode(.large)
            .task {
                await loadHistory()
            }
            .alert("History", isPresented: $showingAlert) {
                Button("OK") { }
            } message: {
                Text(alertMessage)
            }
        }
    }
    
    @MainActor
    private func loadHistory() async {
        isLoading = true
        
        do {
            history = try await apiService.getUserHistory()
        } catch {
            alertMessage = "Error loading history: \(error.localizedDescription)"
            showingAlert = true
        }
        
        isLoading = false
    }
    
    private func deleteItem(at index: Int) {
        // In a real app, you'd call an API to delete the item
        history.remove(at: index)
        // apiService.deleteHistoryItem(id: item["id"])
    }
}

struct HistoryRowView: View {
    let item: [String: Any]
    
    private var type: String {
        item["type"] as? String ?? "unknown"
    }
    
    private var text: String {
        item["text"] as? String ?? ""
    }
    
    private var timestamp: String {
        item["timestamp"] as? String ?? ""
    }
    
    var body: some View {
        HStack {
            // Type Icon
            Image(systemName: type == "audio" ? "waveform" : "video.fill")
                .foregroundColor(type == "audio" ? .blue : .red)
                .font(.title2)
                .frame(width: 40)
            
            VStack(alignment: .leading, spacing: 4) {
                Text(text)
                    .font(.body)
                    .lineLimit(2)
                
                HStack {
                    Text(type.uppercased())
                        .font(.caption)
                        .fontWeight(.semibold)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(type == "audio" ? Color.blue.opacity(0.2) : Color.red.opacity(0.2))
                        .foregroundColor(type == "audio" ? .blue : .red)
                        .cornerRadius(4)
                    
                    Text(timestamp)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            
            Spacer()
            
            Button(action: {
                // Show options menu
            }) {
                Image(systemName: "ellipsis")
                    .foregroundColor(.gray)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture {
            // Show full text in modal
        }
    }
}

#Preview {
    HistoryView()
        .environmentObject(APIService())
}