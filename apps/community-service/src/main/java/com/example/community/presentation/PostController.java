package com.example.community.presentation;

import com.example.community.application.ActorContext;
import com.example.community.application.GetPostUseCase;
import com.example.community.application.PublishPostCommand;
import com.example.community.application.PublishPostUseCase;
import com.example.community.infrastructure.security.ActorContextResolver;
import com.example.community.presentation.dto.PostResponse;
import com.example.community.presentation.dto.PublishPostRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/community/posts")
@RequiredArgsConstructor
public class PostController {

    private final PublishPostUseCase publishPostUseCase;
    private final GetPostUseCase getPostUseCase;

    @PostMapping
    public ResponseEntity<PostResponse> publish(@Valid @RequestBody PublishPostRequest req) {
        ActorContext actor = ActorContextResolver.currentOrThrow();
        PublishPostCommand cmd = new PublishPostCommand(
                actor, req.type(), req.visibility(), req.title(), req.body(), req.mediaUrls()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostResponse.from(publishPostUseCase.execute(cmd)));
    }

    @GetMapping("/{postId}")
    public ResponseEntity<PostResponse> get(@PathVariable String postId) {
        ActorContext actor = ActorContextResolver.currentOrThrow();
        return ResponseEntity.ok(PostResponse.from(getPostUseCase.execute(postId, actor)));
    }
}
