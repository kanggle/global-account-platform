package com.example.community.application;

import com.example.community.application.exception.PostNotFoundException;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.example.community.domain.post.status.ActorType;
import com.example.community.domain.post.status.PostStatus;
import com.example.community.infrastructure.persistence.PostStatusHistoryJpaEntity;
import com.example.community.infrastructure.persistence.PostStatusHistoryJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChangePostStatusUseCase {

    private final PostRepository postRepository;
    private final PostStatusHistoryJpaRepository historyRepository;

    @Transactional
    public void execute(String postId, PostStatus target, ActorType actorType, String actorId, String reason) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));
        PostStatus previous = post.changeStatus(target, actorType);
        postRepository.save(post);
        historyRepository.save(PostStatusHistoryJpaEntity.record(
                postId, previous.name(), target.name(), actorType.name(), actorId, reason));
    }
}
