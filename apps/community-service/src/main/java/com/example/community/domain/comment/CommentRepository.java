package com.example.community.domain.comment;

public interface CommentRepository {

    Comment save(Comment comment);

    long countByPostId(String postId);
}
