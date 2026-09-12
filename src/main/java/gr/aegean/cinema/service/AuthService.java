package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.auth.LoginRequest;
import gr.aegean.cinema.dto.auth.LoginResponse;

public interface AuthService {
    LoginResponse login(LoginRequest request);

    void logout();

    void forceLogout(String targetUsername);
}
