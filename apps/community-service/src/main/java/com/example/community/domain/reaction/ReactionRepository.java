package com.example.community.domain.reaction;

import java.util.Optional;

public interface ReactionRepository {

    Optional<Reaction> find(String postId, String accountId);

    Reaction save(Reaction reaction);

    long countByPostId(String postId);
}
