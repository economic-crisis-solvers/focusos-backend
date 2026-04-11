package com.focusos.controller;

import com.focusos.model.dto.SettingsDtos;
import com.focusos.model.entity.UserSettings;
import com.focusos.repository.UserSettingsRepository;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final UserSettingsRepository settingsRepo;

    public SettingsController(UserSettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    @GetMapping("/settings")
    public SettingsDtos.SettingsResponse getSettings(Authentication auth) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        return toResponse(getOrCreate(userId));
    }

    @PutMapping("/settings")
    public SettingsDtos.SettingsResponse updateSettings(
        @RequestBody SettingsDtos.SettingsUpdateRequest body, Authentication auth
    ) {
        UUID userId = UUID.fromString((String) auth.getPrincipal());
        UserSettings s = getOrCreate(userId);

        if (body.getFocusThreshold() != null) s.setFocusThreshold(body.getFocusThreshold());
        if (body.getWhitelist() != null) s.setWhitelist(body.getWhitelist().toArray(new String[0]));
        if (body.getQuietHours() != null) {
            s.setQuietHoursStart(body.getQuietHours().getStart());
            s.setQuietHoursEnd(body.getQuietHours().getEnd());
        }
        settingsRepo.save(s);
        return toResponse(s);
    }

    private UserSettings getOrCreate(UUID userId) {
        return settingsRepo.findByUserId(userId).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            return settingsRepo.save(s);
        });
    }

    private SettingsDtos.SettingsResponse toResponse(UserSettings s) {
        List<String> whitelist = s.getWhitelist() != null ? Arrays.asList(s.getWhitelist()) : List.of();
        SettingsDtos.QuietHours qh = new SettingsDtos.QuietHours();
        qh.setStart(s.getQuietHoursStart());
        qh.setEnd(s.getQuietHoursEnd());
        return new SettingsDtos.SettingsResponse(s.getFocusThreshold(), whitelist, qh);
    }
}
