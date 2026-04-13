package com.example.community.infrastructure.persistence;

import com.example.community.domain.comment.Comment;
import com.example.community.domain.comment.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CommentRepositoryAdapter implements CommentRepository {

    private final CommentJpaRepository commentJpaRepository;

    @Override
    public Comment save(Comment comment) {
        return commentJpaRepository.save(comment);
    }

    @Override
    public long countByPostId(String postId) {
        return commentJpaRepository.countByPostIdAndDeletedAtIsNull(postId);
    }
}
