package com.example.security.query;

import com.example.security.consumer.AuthEventMapper;
import com.example.security.infrastructure.persistence.LoginHistoryJpaEntity;
import com.example.security.infrastructure.persistence.LoginHistoryJpaRepository;
import com.example.security.query.dto.LoginHistoryView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecurityQueryService {

    private final LoginHistoryJpaRepository loginHistoryJpaRepository;

    public Page<LoginHistoryView> findLoginHistory(String accountId, Instant from, Instant to,
                                                    String outcome, Pageable pageable) {
        Page<LoginHistoryJpaEntity> page = loginHistoryJpaRepository.findByAccountIdAndFilters(
                accountId, from, to, outcome, pageable);

        return page.map(this::toView);
    }

    private LoginHistoryView toView(LoginHistoryJpaEntity entity) {
        return new LoginHistoryView(
                entity.getEventId(),
                entity.getAccountId(),
                entity.getOutcome(),
                AuthEventMapper.maskIp(entity.getIpMasked()),
                entity.getUserAgentFamily(),
                AuthEventMapper.truncateFingerprint(entity.getDeviceFingerprint()),
                entity.getGeoCountry(),
                entity.getOccurredAt()
        );
    }
}
