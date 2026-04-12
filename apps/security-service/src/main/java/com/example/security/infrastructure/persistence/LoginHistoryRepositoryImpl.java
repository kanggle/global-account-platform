package com.example.security.infrastructure.persistence;

import com.example.security.domain.history.LoginHistoryEntry;
import com.example.security.domain.repository.LoginHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LoginHistoryRepositoryImpl implements LoginHistoryRepository {

    private final LoginHistoryJpaRepository jpaRepository;

    @Override
    public void save(LoginHistoryEntry entry) {
        LoginHistoryJpaEntity entity = LoginHistoryJpaEntity.from(
                entry.getEventId(),
                entry.getAccountId(),
                entry.getOutcome().name(),
                entry.getIpMasked(),
                entry.getUserAgentFamily(),
                entry.getDeviceFingerprint(),
                entry.getGeoCountry(),
                entry.getOccurredAt()
        );
        jpaRepository.save(entity);
    }

    @Override
    public boolean existsByEventId(String eventId) {
        return jpaRepository.existsByEventId(eventId);
    }
}
