package com.example.auth.presentation;

import com.example.auth.application.RequestPasswordResetUseCase;
import com.example.auth.application.command.RequestPasswordResetCommand;
import com.example.auth.presentation.dto.PasswordResetRequestRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password-reset endpoints (TASK-BE-108: request, TASK-BE-109: confirm).
 *
 * <p>Currently exposes only the request endpoint; the confirm endpoint will
 * land alongside this controller in BE-109 and will reuse the same
 * {@code /api/auth/password-reset} base path.</p>
 *
 * <p>Both endpoints are unauthenticated — they must be added to the public
 * matchers in {@code SecurityConfig}.</p>
 */
@RestController
@RequestMapping("/api/auth/password-reset")
@RequiredArgsConstructor
public class PasswordResetController {

    private final RequestPasswordResetUseCase requestPasswordResetUseCase;

    /**
     * Issue a password-reset email if the address belongs to a known account.
     *
     * <p>Always returns 204, regardless of whether the email exists, so the
     * endpoint cannot be used to probe account existence.</p>
     */
    @PostMapping("/request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void requestReset(@Valid @RequestBody PasswordResetRequestRequest request) {
        requestPasswordResetUseCase.execute(new RequestPasswordResetCommand(request.email()));
    }
}
