package gr.aegean.cinema.service;

import gr.aegean.cinema.dto.user.*;

public interface UserService {
    UserResponse register(RegisterRequest request);

    UserResponse updateUser(Long targetId, UpdateUserRequest request);

    void updatePassword(Long targetId, UpdatePasswordRequest request);

    UserResponse updateStatus(Long targetId, UpdateStatusRequest request);

    void deleteUser(Long targetId);

    UserResponse getUser(Long targetId);
}
