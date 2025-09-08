import Foundation
import Security

@MainActor
class AuthenticationManager: ObservableObject {
    @Published var isAuthenticated = false
    @Published var currentUser: [String: Any]?
    @Published var isLoading = false
    
    private let apiService = APIService()
    private let keychainService = KeychainService()
    
    init() {
        loadStoredAuthentication()
    }
    
    func loadStoredAuthentication() {
        if let token = keychainService.getToken(),
           !isTokenExpired(token) {
            isAuthenticated = true
            currentUser = decodeToken(token)
            apiService.setAuthToken(token)
        }
    }
    
    func login(email: String, password: String) async throws {
        isLoading = true
        defer { isLoading = false }
        
        let response = try await apiService.login(email: email, password: password)
        
        if let token = response["token"] as? String,
           let user = response["user"] as? [String: Any] {
            keychainService.storeToken(token)
            isAuthenticated = true
            currentUser = user
            apiService.setAuthToken(token)
        } else {
            throw AuthError.invalidCredentials
        }
    }
    
    func register(email: String, password: String, name: String) async throws {
        isLoading = true
        defer { isLoading = false }
        
        let response = try await apiService.register(email: email, password: password, name: name)
        
        if let token = response["token"] as? String,
           let user = response["user"] as? [String: Any] {
            keychainService.storeToken(token)
            isAuthenticated = true
            currentUser = user
            apiService.setAuthToken(token)
        } else {
            throw AuthError.registrationFailed
        }
    }
    
    func logout() {
        keychainService.deleteToken()
        isAuthenticated = false
        currentUser = nil
        apiService.clearAuthToken()
    }
    
    private func isTokenExpired(_ token: String) -> Bool {
        // Simple JWT expiration check
        // In a real app, you'd decode the JWT and check the exp claim
        return false
    }
    
    private func decodeToken(_ token: String) -> [String: Any]? {
        // Simple JWT decode - in real app, use proper JWT library
        return nil
    }
}

class KeychainService {
    private let service = "com.purrfectbytes.app"
    private let tokenKey = "auth_token"
    
    func storeToken(_ token: String) {
        let data = token.data(using: .utf8)!
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenKey,
            kSecValueData as String: data
        ]
        
        // Delete existing item
        SecItemDelete(query as CFDictionary)
        
        // Add new item
        SecItemAdd(query as CFDictionary, nil)
    }
    
    func getToken() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenKey,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        
        guard status == errSecSuccess,
              let data = item as? Data,
              let token = String(data: data, encoding: .utf8) else {
            return nil
        }
        
        return token
    }
    
    func deleteToken() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: tokenKey
        ]
        
        SecItemDelete(query as CFDictionary)
    }
}

enum AuthError: Error {
    case invalidCredentials
    case registrationFailed
    case tokenExpired
    
    var localizedDescription: String {
        switch self {
        case .invalidCredentials:
            return "Invalid email or password"
        case .registrationFailed:
            return "Registration failed. Please try again."
        case .tokenExpired:
            return "Session expired. Please login again."
        }
    }
}