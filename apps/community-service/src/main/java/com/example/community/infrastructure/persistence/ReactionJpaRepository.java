package com.example.community.infrastructure.persistence;

import com.example.community.domain.reaction.Reaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReactionJpaRepository extends JpaRepository<Reaction, Reaction.ReactionId> {

    Optional<Reaction> findByPostIdAndAccountId(String postId, String accountId);

    long countByPostId(String postId);
}
