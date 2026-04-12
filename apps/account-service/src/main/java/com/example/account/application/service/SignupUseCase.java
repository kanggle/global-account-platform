package com.example.account.application.service;

import com.example.account.application.command.SignupCommand;
import com.example.account.application.event.AccountEventPublisher;
import com.example.account.application.exception.AccountAlreadyExistsException;
import com.example.account.application.result.SignupResult;
import com.example.account.domain.account.Account;
import com.example.account.domain.profile.Profile;
import com.example.account.domain.repository.AccountRepository;
import com.example.account.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SignupUseCase {

    private final AccountRepository accountRepository;
    private final ProfileRepository profileRepository;
    private final AccountEventPublisher eventPublisher;

    @Transactional
    public SignupResult execute(SignupCommand command) {
        // Check email uniqueness (primary defense: DB unique constraint)
        if (accountRepository.existsByEmail(command.email().trim().toLowerCase())) {
            throw new AccountAlreadyExistsException(command.email());
        }

        try {
            // Create account (Email value object validates and normalizes)
            Account account = Account.create(command.email());
            account = accountRepository.save(account);

            // Create profile
            Profile profile = Profile.create(
                    account.getId(),
                    command.displayName(),
                    command.locale(),
                    command.timezone()
            );
            profileRepository.save(profile);

            // Publish outbox event
            eventPublisher.publishAccountCreated(
                    account.getId(),
                    account.getEmail(),
                    account.getStatus().name(),
                    profile.getLocale(),
                    account.getCreatedAt()
            );

            return SignupResult.from(account);
        } catch (DataIntegrityViolationException e) {
            // Race condition: concurrent signup with same email
            throw new AccountAlreadyExistsException(command.email());
        }
    }
}
