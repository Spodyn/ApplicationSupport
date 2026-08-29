package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/channels")
class ChannelAdminController {

    private final ChannelService channels;

    ChannelAdminController(ChannelService channels) {
        this.channels = channels;
    }

    @GetMapping
    List<ChannelView> list(Authentication actor) {
        return channels.list(actor);
    }

    @PatchMapping("/{channelId}")
    ChannelView setIgnored(
            @PathVariable UUID channelId,
            @Valid @RequestBody ChannelOperationalStateRequest input,
            Authentication actor) {
        return channels.setIgnored(actor, channelId, Boolean.TRUE.equals(input.ignored()));
    }

    record ChannelOperationalStateRequest(@NotNull Boolean ignored) {
    }
}
