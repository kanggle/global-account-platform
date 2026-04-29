package com.example.community.presentation.dto;

import java.time.Instant;
import java.util.List;

public record UpdatePostResponse(
        String postId,
        String title,
        String body,
        List<String> mediaUrls,
        Instant updatedAt
) {
}
