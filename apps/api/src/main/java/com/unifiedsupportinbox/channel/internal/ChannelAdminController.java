package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.channel.ChannelView;
import jakarta.validation.Valid;
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
    ChannelView update(
            @PathVariable UUID channelId,
            @Valid @RequestBody ChannelUpdateRequest input,
            Authentication actor) {
        return channels.update(
                actor,
                channelId,
                input.ignored(),
                input.customerId(),
                input.groupingStrategy());
    }

    record ChannelUpdateRequest(
            Boolean ignored,
            UUID customerId,
            ChannelGroupingStrategy groupingStrategy) {
    }
}
