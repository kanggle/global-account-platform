package com.example.account.infrastructure.persistence;

import com.example.account.domain.profile.Profile;
import com.example.account.domain.repository.ProfileRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProfileJpaRepository extends JpaRepository<Profile, Long>, ProfileRepository {
}
