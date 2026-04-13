package com.example.community.application;

import com.example.community.application.event.CommunityEventPublisher;
import com.example.community.application.exception.PermissionDeniedException;
import com.example.community.domain.access.AccountProfileLookup;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.example.community.domain.post.PostType;
import com.example.community.domain.post.status.ActorType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PublishPostUseCase {

    private static final String ROLE_ARTIST = "ARTIST";

    private final PostRepository postRepository;
    private final CommunityEventPublisher eventPublisher;
    private final AccountProfileLookup accountProfileLookup;
    private final ObjectMapper objectMapper;

    @Transactional
    public PostView execute(PublishPostCommand cmd) {
        ActorContext actor = cmd.actor();
        if (cmd.type() == PostType.ARTIST_POST && !actor.hasRole(ROLE_ARTIST)) {
            throw new PermissionDeniedException("ARTIST role required to publish ARTIST_POST");
        }

        String mediaUrlsJson = null;
        if (cmd.mediaUrls() != null && !cmd.mediaUrls().isEmpty()) {
            try {
                mediaUrlsJson = objectMapper.writeValueAsString(cmd.mediaUrls());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid mediaUrls");
            }
        }

        Post post = Post.createDraft(
                actor.accountId(),
                cmd.type(),
                cmd.visibility(),
                cmd.title(),
                cmd.body(),
                mediaUrlsJson
        );
        post.publish(ActorType.AUTHOR);
        Post saved = postRepository.save(post);

        eventPublisher.publishPostPublished(
                saved.getId(),
                saved.getAuthorAccountId(),
                saved.getType().name(),
                saved.getVisibility().name(),
                saved.getPublishedAt()
        );

        String displayName = accountProfileLookup.displayNameOf(saved.getAuthorAccountId());

        return new PostView(
                saved.getId(),
                saved.getType(),
                saved.getVisibility(),
                saved.getStatus(),
                saved.getAuthorAccountId(),
                displayName,
                saved.getTitle(),
                saved.getBody(),
                0L,
                0L,
                null,
                saved.getPublishedAt(),
                saved.getCreatedAt()
        );
    }
}
