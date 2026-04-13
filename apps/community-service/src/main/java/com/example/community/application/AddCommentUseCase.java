package com.example.community.application;

import com.example.community.application.event.CommunityEventPublisher;
import com.example.community.application.exception.MembershipRequiredException;
import com.example.community.application.exception.PostNotFoundException;
import com.example.community.domain.access.AccountProfileLookup;
import com.example.community.domain.access.ContentAccessChecker;
import com.example.community.domain.comment.Comment;
import com.example.community.domain.comment.CommentRepository;
import com.example.community.domain.post.Post;
import com.example.community.domain.post.PostRepository;
import com.example.community.domain.post.PostVisibility;
import com.example.community.domain.post.status.PostStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AddCommentUseCase {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ContentAccessChecker contentAccessChecker;
    private final AccountProfileLookup accountProfileLookup;
    private final CommunityEventPublisher eventPublisher;

    public record CommentView(String commentId, String postId, String authorAccountId,
                              String authorDisplayName, String body, java.time.Instant createdAt) {}

    @Transactional
    public CommentView execute(String postId, String body, ActorContext actor) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new PostNotFoundException(postId));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new PostNotFoundException(postId);
        }

        if (post.getVisibility() == PostVisibility.MEMBERS_ONLY
                && !post.getAuthorAccountId().equals(actor.accountId())) {
            boolean allowed = contentAccessChecker.check(actor.accountId(), GetPostUseCase.REQUIRED_PLAN_LEVEL);
            if (!allowed) {
                throw new MembershipRequiredException();
            }
        }

        Comment comment = Comment.create(postId, actor.accountId(), body);
        Comment saved = commentRepository.save(comment);

        eventPublisher.publishCommentCreated(
                saved.getId(),
                saved.getPostId(),
                post.getAuthorAccountId(),
                saved.getAuthorAccountId(),
                saved.getCreatedAt()
        );

        String displayName = accountProfileLookup.displayNameOf(saved.getAuthorAccountId());

        return new CommentView(
                saved.getId(),
                saved.getPostId(),
                saved.getAuthorAccountId(),
                displayName,
                saved.getBody(),
                saved.getCreatedAt()
        );
    }
}
