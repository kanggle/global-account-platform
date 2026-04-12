package com.example.security.domain.repository;

import com.example.security.domain.history.LoginHistoryEntry;

public interface LoginHistoryRepository {

    void save(LoginHistoryEntry entry);

    boolean existsByEventId(String eventId);
}
