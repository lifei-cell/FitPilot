package com.fitpilot.user.application;

import com.fitpilot.user.domain.User;
import com.fitpilot.user.domain.UserProfile;
import com.fitpilot.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UserAccountService {
    private final UserRepository repository;

    public UserAccountService(UserRepository repository) {
        this.repository = repository;
    }

    public boolean usernameExists(String username) {
        return repository.usernameExists(username);
    }

    public boolean emailExists(String email) {
        return repository.emailExists(email);
    }

    public Account create(String username, String email, String passwordHash, LocalDateTime now) {
        User user = new User();
        user.username = username;
        user.email = email;
        user.passwordHash = passwordHash;
        user.status = 1;
        user.createdAt = now;
        user.updatedAt = now;
        repository.insert(user);

        UserProfile profile = new UserProfile();
        profile.userId = user.id;
        profile.createdAt = now;
        profile.updatedAt = now;
        repository.insertProfile(profile);
        return toAccount(user);
    }

    public Optional<Account> findByUsername(String username) {
        return repository.findByUsername(username).map(this::toAccount);
    }

    public Optional<Account> findById(long id) {
        return repository.findById(id).map(this::toAccount);
    }

    private Account toAccount(User user) {
        return new Account(user.id, user.username, user.email, user.passwordHash, user.status);
    }

    public record Account(long id, String username, String email, String passwordHash, Short status) {}
}
