package com.unifiedsupportinbox.provider.internal;

import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class HistoryRecoveryWorkerTests {
    @ParameterizedTest @ValueSource(strings={"SLACK","TEAMS"})
    void providerOutageRetainsDurableTaskForRetry(String provider) {
        var store=mock(HistoryRecoveryStore.class);var adapter=mock(HistoryRecoveryAdapter.class);
        when(adapter.provider()).thenReturn(provider);
        var task=new HistoryRecoveryAdapter.Task(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"channel",provider,"manual",null,Instant.now(),"","cursor",UUID.randomUUID(),new ObjectMapper().createObjectNode());
        when(adapter.fetch(task)).thenThrow(new HistoryRecoveryAdapter.Unavailable(provider+"_HTTP_503",Duration.ofSeconds(30)));
        new HistoryRecoveryWorker(store,List.of(adapter)).process(task);
        verify(store).fail(task,provider+"_HTTP_503",Duration.ofSeconds(30));
        verify(store,never()).commit(any(),any(),any());
    }
}
