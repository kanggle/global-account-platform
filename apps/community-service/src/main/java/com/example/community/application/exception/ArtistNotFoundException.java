package com.example.community.application.exception;

public class ArtistNotFoundException extends RuntimeException {
    public ArtistNotFoundException(String artistAccountId) {
        super("ARTIST_NOT_FOUND: " + artistAccountId);
    }
}
