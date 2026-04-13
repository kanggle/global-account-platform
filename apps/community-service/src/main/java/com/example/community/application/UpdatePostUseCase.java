package com.example.community.application;

import com.example.community.application.exception.PermissionDeniedException;
import com.example.community.application.exception.PostNotFoundException;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UpdatePostUseCase {

    private final PostRepository postRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void execute(String postId, ActorContext actor, String title, String body, List<String> mediaUrls) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));
        if (!post.getAuthorAccountId().equals(actor.accountId())) {
            throw new PermissionDeniedException("Only the author can update this post");
        }
        String mediaUrlsJson = null;
        if (mediaUrls != null && !mediaUrls.isEmpty()) {
            try {
                mediaUrlsJson = objectMapper.writeValueAsString(mediaUrls);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid mediaUrls");
            }
        }
        post.updateContent(title, body, mediaUrlsJson);
        postRepository.save(post);
    }
}
