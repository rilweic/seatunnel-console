package com.dataintegration.console.server.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.engine.MasterStatus;
import com.dataintegration.console.server.message.MessageCategory;
import com.dataintegration.console.server.message.Messages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class ClusterWatcherTest {

    private static final List<MasterStatus> DOWN = List.of(new MasterStatus("http://m:8080", false, null, null, 0, "refused"));
    private static final List<MasterStatus> UP = List.of(new MasterStatus("http://m:8080", true, true, "3.0.1", 5, null));

    @Mock private EngineClient engine;
    @Mock private Messages messages;

    @Test
    void reportsOnceAfterConsecutiveFailuresAndResolvesWhenBack() {
        ClusterWatcher watcher = new ClusterWatcher(engine, messages);
        when(messages.system(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(messages.resolve(anyString(), eq(MessageCategory.CLUSTER))).thenReturn(true);
        when(engine.probeMasters()).thenReturn(DOWN);
        for (int i = 0; i < ClusterWatcher.FAILURES_TO_REPORT - 1; i++) {
            watcher.check();
        }
        verify(messages, never()).system(any(), any(), anyString(), anyString(), anyString(), anyString());

        watcher.check();
        watcher.check();
        verify(messages, times(1)).system(eq(MessageCategory.CLUSTER), any(), anyString(), anyString(), anyString(), anyString());

        when(engine.probeMasters()).thenReturn(UP);
        watcher.check();
        watcher.check();
        verify(messages, times(1)).resolve(anyString(), eq(MessageCategory.CLUSTER));
    }

    @Test
    void writesThatFailAreTriedAgainOnTheNextCheck() {
        ClusterWatcher watcher = new ClusterWatcher(engine, messages);
        when(messages.system(any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(false, true);
        when(messages.resolve(anyString(), eq(MessageCategory.CLUSTER))).thenReturn(false, true);
        when(engine.probeMasters()).thenReturn(DOWN);
        for (int i = 0; i < ClusterWatcher.FAILURES_TO_REPORT + 2; i++) {
            watcher.check();
        }
        // the third check's write failed, the fourth one's went through, the fifth has nothing to do
        verify(messages, times(2)).system(eq(MessageCategory.CLUSTER), any(), anyString(), anyString(), anyString(), anyString());

        when(engine.probeMasters()).thenReturn(UP);
        watcher.check();
        watcher.check();
        watcher.check();
        verify(messages, times(2)).resolve(anyString(), eq(MessageCategory.CLUSTER));
    }

    @Test
    void aSingleMissIsNotReported() {
        ClusterWatcher watcher = new ClusterWatcher(engine, messages);
        when(engine.probeMasters()).thenReturn(UP, DOWN, UP, DOWN, DOWN, UP);
        for (int i = 0; i < 6; i++) {
            watcher.check();
        }
        verify(messages, never()).system(any(), any(), anyString(), anyString(), anyString(), anyString());
    }
}
