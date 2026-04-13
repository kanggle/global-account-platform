package com.example.community.infrastructure.persistence;

import com.example.community.domain.comment.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommentJpaRepository extends JpaRepository<Comment, String> {
    long countByPostIdAndDeletedAtIsNull(String postId);
}
