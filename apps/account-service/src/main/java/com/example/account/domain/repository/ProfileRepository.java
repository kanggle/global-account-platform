package com.example.account.domain.repository;

import com.example.account.domain.profile.Profile;

import java.util.Optional;

public interface ProfileRepository {

    Profile save(Profile profile);

    Optional<Profile> findByAccountId(String accountId);
}
