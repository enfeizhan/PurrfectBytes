import SwiftUI

struct LoginView: View {
    @EnvironmentObject var authManager: AuthenticationManager
    @State private var email = ""
    @State private var password = ""
    @State private var name = ""
    @State private var isLoginMode = true
    @State private var showingAlert = false
    @State private var alertMessage = ""
    @State private var isLoading = false
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    // Logo
                    Image(systemName: "cat.fill")
                        .font(.system(size: 80))
                        .foregroundColor(.blue)
                        .padding(.top, 50)
                    
                    Text("PurrfectBytes")
                        .font(.largeTitle)
                        .fontWeight(.bold)
                    
                    Text(isLoginMode ? "Welcome Back!" : "Create Account")
                        .font(.title2)
                        .foregroundColor(.secondary)
                    
                    // Form Fields
                    VStack(spacing: 16) {
                        if !isLoginMode {
                            TextField("Name", text: $name)
                                .textFieldStyle(RoundedBorderTextFieldStyle())
                                .autocapitalization(.words)
                        }
                        
                        TextField("Email", text: $email)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .autocapitalization(.none)
                            .keyboardType(.emailAddress)
                        
                        SecureField("Password", text: $password)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                    }
                    .padding(.horizontal)
                    
                    // Submit Button
                    Button(action: handleSubmit) {
                        HStack {
                            if isLoading {
                                ProgressView()
                                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                                    .scaleEffect(0.8)
                            }
                            Text(isLoginMode ? "Login" : "Register")
                                .fontWeight(.semibold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(10)
                    }
                    .disabled(isLoading || email.isEmpty || password.isEmpty || (!isLoginMode && name.isEmpty))
                    .padding(.horizontal)
                    
                    // Toggle Mode Button
                    Button(action: {
                        withAnimation {
                            isLoginMode.toggle()
                            clearForm()
                        }
                    }) {
                        Text(isLoginMode ? "Don't have an account? Register" : "Already have an account? Login")
                            .foregroundColor(.blue)
                    }
                    .padding(.top)
                    
                    Spacer(minLength: 50)
                }
            }
            .navigationBarHidden(true)
            .alert(isPresented: $showingAlert) {
                Alert(
                    title: Text("Authentication"),
                    message: Text(alertMessage),
                    dismissButton: .default(Text("OK"))
                )
            }
        }
    }
    
    private func handleSubmit() {
        guard validateForm() else { return }
        
        isLoading = true
        
        Task {
            do {
                if isLoginMode {
                    try await authManager.login(email: email, password: password)
                } else {
                    try await authManager.register(email: email, password: password, name: name)
                }
            } catch {
                await MainActor.run {
                    alertMessage = error.localizedDescription
                    showingAlert = true
                    isLoading = false
                }
            }
        }
    }
    
    private func validateForm() -> Bool {
        if email.isEmpty || password.isEmpty {
            alertMessage = "Please fill in all fields"
            showingAlert = true
            return false
        }
        
        if !email.contains("@") {
            alertMessage = "Please enter a valid email"
            showingAlert = true
            return false
        }
        
        if !isLoginMode && name.isEmpty {
            alertMessage = "Please enter your name"
            showingAlert = true
            return false
        }
        
        if !isLoginMode && password.count < 6 {
            alertMessage = "Password must be at least 6 characters"
            showingAlert = true
            return false
        }
        
        return true
    }
    
    private func clearForm() {
        email = ""
        password = ""
        name = ""
    }
}

#Preview {
    LoginView()
        .environmentObject(AuthenticationManager())
}