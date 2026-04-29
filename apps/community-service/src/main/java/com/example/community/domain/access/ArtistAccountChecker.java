package com.example.community.domain.access;

import com.example.community.application.exception.ArtistNotFoundException;

/**
 * Port for verifying that an artist account exists in account-service.
 * Implementations must throw {@link ArtistNotFoundException} when the account is
 * confirmed missing (404). Other downstream errors should be fail-open: log and
 * return without throwing, so that account-service availability does not couple
 * to follow operations.
 */
public interface ArtistAccountChecker {

    void assertExists(String artistAccountId) throws ArtistNotFoundException;
}
