import Foundation

struct TTSRequest {
    let text: String
    let language: String
    let slow: Bool
    
    init(text: String, language: String = "en", slow: Bool = false) {
        self.text = text
        self.language = language
        self.slow = slow
    }
}

struct VideoRequest {
    let text: String
    let language: String
    let videoType: String
    let duration: Int
    let backgroundColor: String
    let textColor: String
    let fontSize: Int
    
    init(text: String, language: String = "en", videoType: String = "simple", 
         duration: Int = 10, backgroundColor: String = "#000000", 
         textColor: String = "#FFFFFF", fontSize: Int = 48) {
        self.text = text
        self.language = language
        self.videoType = videoType
        self.duration = duration
        self.backgroundColor = backgroundColor
        self.textColor = textColor
        self.fontSize = fontSize
    }
}

@MainActor
class APIService: ObservableObject {
    private let baseURL = "http://localhost:8000" // Change for production
    private var authToken: String?
    
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()
    
    func setAuthToken(_ token: String) {
        authToken = token
    }
    
    func clearAuthToken() {
        authToken = nil
    }
    
    // MARK: - Authentication
    
    func login(email: String, password: String) async throws -> [String: Any] {
        let url = URL(string: "\(baseURL)/auth/login")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body = [
            "email": email,
            "password": password
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await session.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw APIError.invalidResponse
        }
        
        let result = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return result ?? [:]
    }
    
    func register(email: String, password: String, name: String) async throws -> [String: Any] {
        let url = URL(string: "\(baseURL)/auth/register")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body = [
            "email": email,
            "password": password,
            "name": name
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await session.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw APIError.invalidResponse
        }
        
        let result = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return result ?? [:]
    }
    
    // MARK: - TTS & Video
    
    func convertTextToSpeech(request: TTSRequest) async throws -> [String: Any] {
        let url = URL(string: "\(baseURL)/convert")!
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        
        let boundary = UUID().uuidString
        urlRequest.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        if let token = authToken {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let body = createMultipartBody(boundary: boundary, parameters: [
            "text": request.text,
            "language": request.language,
            "slow": request.slow ? "true" : "false"
        ])
        urlRequest.httpBody = body
        
        let (data, response) = try await session.data(for: urlRequest)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw APIError.invalidResponse
        }
        
        let result = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return result ?? [:]
    }
    
    func downloadAudio(filename: String) async throws -> URL {
        let url = URL(string: "\(baseURL)/download/\(filename)")!
        var request = URLRequest(url: url)
        
        if let token = authToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let (data, _) = try await session.data(for: request)
        
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        try data.write(to: tempURL)
        
        return tempURL
    }
    
    func createVideo(request: VideoRequest) async throws -> [String: Any] {
        let url = URL(string: "\(baseURL)/create_video")!
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        
        let boundary = UUID().uuidString
        urlRequest.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        if let token = authToken {
            urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let body = createMultipartBody(boundary: boundary, parameters: [
            "text": request.text,
            "language": request.language,
            "video_type": request.videoType,
            "duration": String(request.duration),
            "background_color": request.backgroundColor,
            "text_color": request.textColor,
            "font_size": String(request.fontSize)
        ])
        urlRequest.httpBody = body
        
        let (data, response) = try await session.data(for: urlRequest)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw APIError.invalidResponse
        }
        
        let result = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return result ?? [:]
    }
    
    func downloadVideo(filename: String) async throws -> URL {
        let url = URL(string: "\(baseURL)/download_video/\(filename)")!
        var request = URLRequest(url: url)
        
        if let token = authToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let (data, _) = try await session.data(for: request)
        
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        try data.write(to: tempURL)
        
        return tempURL
    }
    
    func getUserHistory() async throws -> [[String: Any]] {
        let url = URL(string: "\(baseURL)/user/history")!
        var request = URLRequest(url: url)
        
        if let token = authToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        
        let (data, response) = try await session.data(for: request)
        
        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw APIError.invalidResponse
        }
        
        let result = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        return result ?? []
    }
    
    // MARK: - Helper Methods
    
    private func createMultipartBody(boundary: String, parameters: [String: String]) -> Data {
        var body = Data()
        
        for (key, value) in parameters {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        return body
    }
}

enum APIError: Error {
    case invalidResponse
    case noData
    case decodingError
    
    var localizedDescription: String {
        switch self {
        case .invalidResponse:
            return "Invalid server response"
        case .noData:
            return "No data received"
        case .decodingError:
            return "Failed to decode response"
        }
    }
}