package com.example.security.application;

import com.example.security.domain.history.LoginHistoryEntry;
import com.example.security.domain.repository.LoginHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordLoginHistoryUseCase {

    private final LoginHistoryRepository loginHistoryRepository;

    @Transactional
    public void execute(LoginHistoryEntry entry) {
        loginHistoryRepository.save(entry);
        log.info("Recorded login history: eventId={}, accountId={}, outcome={}",
                entry.getEventId(), entry.getAccountId(), entry.getOutcome());
    }
}
