package com.fitpilot.auth.application;

import com.fitpilot.auth.dto.AuthDtos;
import com.fitpilot.common.exception.BusinessException;
import com.fitpilot.common.exception.ErrorCode;
import com.fitpilot.common.security.JwtService;
import com.fitpilot.user.domain.User;
import com.fitpilot.user.domain.UserProfile;
import com.fitpilot.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService {
    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;

    public AuthService(UserRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RefreshTokenService refreshTokens) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
    }

    @Transactional
    public AuthDtos.UserView register(AuthDtos.RegisterRequest request) {
        if (repository.usernameExists(request.username())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS, "username already exists", HttpStatus.CONFLICT);
        }
        if (repository.emailExists(request.email())) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS, "email already exists", HttpStatus.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.username = request.username();
        user.email = request.email();
        user.passwordHash = passwordEncoder.encode(request.password());
        user.status = 1;
        user.createdAt = now;
        user.updatedAt = now;
        repository.insert(user);

        UserProfile profile = new UserProfile();
        profile.userId = user.id;
        profile.createdAt = now;
        profile.updatedAt = now;
        repository.insertProfile(profile);
        return new AuthDtos.UserView(user.id, user.username, user.email);
    }

    public Session login(AuthDtos.LoginRequest request) {
        User user = repository.findByUsername(request.username())
                .orElseThrow(() -> new BusinessException(ErrorCode.PASSWORD_INCORRECT, "invalid username or password", HttpStatus.UNAUTHORIZED));
        if (user.status == null || user.status != 1) {
            throw new BusinessException(ErrorCode.USER_DISABLED, "user is not active", HttpStatus.FORBIDDEN);
        }
        if (!passwordEncoder.matches(request.password(), user.passwordHash)) {
            throw new BusinessException(ErrorCode.PASSWORD_INCORRECT, "invalid username or password", HttpStatus.UNAUTHORIZED);
        }
        return session(user.id, user.username);
    }

    public Session refresh(String token) {
        RefreshTokenService.RotatedToken rotated = refreshTokens.rotate(token);
        User user = repository.findById(rotated.userId()).orElse(null);
        if (user == null || user.status == null || user.status != 1) {
            refreshTokens.revoke(rotated.token().value());
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED, "refresh session is no longer active",
                    HttpStatus.UNAUTHORIZED);
        }
        return new Session(new AuthDtos.LoginView(jwtService.issue(user.id, user.username),
                jwtService.expirationSeconds()), rotated.token());
    }

    public void logout(String token) { refreshTokens.revoke(token); }

    private Session session(long userId, String username) {
        return new Session(new AuthDtos.LoginView(jwtService.issue(userId, username), jwtService.expirationSeconds()),
                refreshTokens.issue(userId, username));
    }

    public record Session(AuthDtos.LoginView view, RefreshTokenService.IssuedToken refreshToken) {}
}
