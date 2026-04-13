package com.example.community.application;

import com.example.community.application.event.CommunityEventPublisher;
import com.example.community.application.exception.PermissionDeniedException;
import com.example.community.domain.access.AccountProfileLookup;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.example.community.domain.post.PostType;
import com.example.community.domain.post.PostVisibility;
import com.example.community.domain.post.status.PostStatusHistoryEntry;
import com.example.community.domain.post.status.PostStatusHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class PublishPostUseCaseTest {

    @Mock PostRepository postRepository;
    @Mock PostStatusHistoryRepository historyRepository;
    @Mock CommunityEventPublisher eventPublisher;
    @Mock AccountProfileLookup accountProfileLookup;

    PublishPostUseCase useCase;

    @Test
    void artist_publishes_public_post_ok() {
        ObjectMapper mapper = new ObjectMapper();
        useCase = new PublishPostUseCase(postRepository, historyRepository, eventPublisher, accountProfileLookup, mapper);

        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountProfileLookup.displayNameOf("artist-1")).thenReturn("Stage Name");

        PublishPostCommand cmd = new PublishPostCommand(
                new ActorContext("artist-1", Set.of("ARTIST")),
                PostType.ARTIST_POST, PostVisibility.PUBLIC,
                "Hello", "Body", List.of()
        );
        PostView view = useCase.execute(cmd);

        assertThat(view.authorAccountId()).isEqualTo("artist-1");
        assertThat(view.authorDisplayName()).isEqualTo("Stage Name");
        assertThat(view.type()).isEqualTo(PostType.ARTIST_POST);
        verify(eventPublisher).publishPostPublished(any(), any(), any(), any(), any());
        verify(historyRepository).save(any(PostStatusHistoryEntry.class));
    }

    @Test
    void fan_cannot_publish_artist_post() {
        useCase = new PublishPostUseCase(postRepository, historyRepository, eventPublisher, accountProfileLookup, new ObjectMapper());

        PublishPostCommand cmd = new PublishPostCommand(
                new ActorContext("fan-1", Set.of("FAN")),
                PostType.ARTIST_POST, PostVisibility.PUBLIC,
                null, "body", null
        );

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(PermissionDeniedException.class);
    }
}
