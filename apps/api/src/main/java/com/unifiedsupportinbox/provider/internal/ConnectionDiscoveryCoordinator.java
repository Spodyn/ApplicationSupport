package com.unifiedsupportinbox.provider.internal;

import com.unifiedsupportinbox.integration.*;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="usi.providers.channel-discovery.worker.enabled",havingValue="true",matchIfMissing=true)
class ConnectionDiscoveryCoordinator {
    private final java.util.concurrent.ScheduledExecutorService heartbeats=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"discovery-lease");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy void close(){heartbeats.shutdownNow();}
    private final ProviderConnectionConfiguration connections;
    private final List<ProviderChannelDiscovery> discovery;
    ConnectionDiscoveryCoordinator(ProviderConnectionConfiguration connections,List<ProviderChannelDiscovery> discovery) {
        this.connections=connections;this.discovery=discovery;
    }
    @Scheduled(fixedDelayString="${usi.providers.channel-discovery.poll-interval:5s}")
    void poll() { connections.claimDue().ifPresent(this::process); }
    void process(ProviderConnectionConfiguration.Claim claim) {
        var heartbeat=heartbeats.scheduleAtFixedRate(()->{try{connections.renew(claim);}catch(RuntimeException ignored){/* A failed renewal cannot authorize a stale snapshot. */}},60,60,java.util.concurrent.TimeUnit.SECONDS);
        try {
            int count=discovery.stream().filter(d->d.provider()==claim.provider()).findFirst().orElseThrow().discover(claim);
            connections.finished(claim,"READY",null,count,Duration.ofSeconds(30));
        } catch(ProviderChannelDiscovery.Failure failure) {
            connections.finished(claim,failure.code.contains("BOT_CONFIGURATION")?"MISSING_BOT_CONFIGURATION":
                    failure.code.contains("403")||failure.code.contains("INSTALLATION")?"INSTALLATION_REQUIRED":"ERROR",
                    failure.code,0,failure.retryAfter==null?Duration.ofMinutes(1):failure.retryAfter);
        } catch(RuntimeException failure) {
            connections.finished(claim,"ERROR","CHANNEL_DISCOVERY_UNAVAILABLE",0,Duration.ofSeconds(30));
        } finally {heartbeat.cancel(false);}
    }
}
