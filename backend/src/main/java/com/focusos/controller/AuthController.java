package com.focusos.controller;

import com.focusos.model.dto.AuthDtos;
import com.focusos.model.entity.User;
import com.focusos.model.entity.UserSettings;
import com.focusos.repository.UserRepository;
import com.focusos.repository.UserSettingsRepository;
import com.focusos.security.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final UserSettingsRepository settingsRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserRepository userRepo, UserSettingsRepository settingsRepo,
                          PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepo        = userRepo;
        this.settingsRepo    = settingsRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil         = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest body) {
        if (userRepo.existsByEmail(body.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = new User();
        user.setEmail(body.getEmail());
        user.setName(body.getName());
        user.setPasswordHash(passwordEncoder.encode(body.getPassword()));
        userRepo.save(user);

        UserSettings settings = new UserSettings();
        settings.setUserId(user.getId());
        settingsRepo.save(settings);

        String token = jwtUtil.generateToken(user.getId().toString());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthDtos.AuthResponse(user.getId().toString(), token));
    }

    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest body) {
        User user = userRepo.findByEmail(body.getEmail())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(body.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtUtil.generateToken(user.getId().toString());
        return new AuthDtos.AuthResponse(user.getId().toString(), token);
    }
}
