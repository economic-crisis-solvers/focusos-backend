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

import java.util.UUID;

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

    /**
     * Legacy register endpoint — kept for backwards compatibility.
     * New clients should use Supabase Auth directly (signUp / signInWithOAuth).
     * The Supabase Auth user UUID will be used as userId automatically
     * since JwtUtil now verifies Supabase tokens.
     */
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

        // Return user ID — client should get token from Supabase Auth
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new AuthDtos.AuthResponse(user.getId().toString(), "use-supabase-auth-token"));
    }

    /**
     * Legacy login endpoint — kept for backwards compatibility.
     * New clients should use Supabase Auth: supabase.auth.signInWithPassword()
     */
    @PostMapping("/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest body) {
        User user = userRepo.findByEmail(body.getEmail())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(body.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return new AuthDtos.AuthResponse(user.getId().toString(), "use-supabase-auth-token");
    }

    /**
     * Auto-provision user settings for Supabase Auth users.
     * Called after Supabase Auth login to ensure user has settings row.
     * The userId here is the Supabase Auth UUID from the JWT sub claim.
     */
    @PostMapping("/provision")
    public ResponseEntity<Void> provision(@RequestHeader("Authorization") String authHeader) {
        // Extract userId from Supabase JWT
        String token = authHeader.replace("Bearer ", "");
        String userId = jwtUtil.extractUserId(token);

        // Create settings row if it doesn't exist
        UUID userUUID = UUID.fromString(userId);
        if (settingsRepo.findByUserId(userUUID).isEmpty()) {
            UserSettings settings = new UserSettings();
            settings.setUserId(userUUID);
            settingsRepo.save(settings);
        }

        return ResponseEntity.ok().build();
    }
}
