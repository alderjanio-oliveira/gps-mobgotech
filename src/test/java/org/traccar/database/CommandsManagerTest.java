package org.traccar.database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.ServerManager;
import org.traccar.broadcast.BroadcastService;
import org.traccar.command.CommandSender;
import org.traccar.command.CommandSenderManager;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.QueuedCommand;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class CommandsManagerTest {

    private Storage storage;
    private BlockStateManager blockState;
    private CommandSenderManager senders;
    private CommandsManager manager;
    private Command command;
    private Device device;

    @BeforeEach
    public void setup() throws Exception {
        storage = mock(Storage.class);
        blockState = mock(BlockStateManager.class);
        senders = mock(CommandSenderManager.class);
        manager = new CommandsManager(storage, mock(ServerManager.class), null,
                mock(ConnectionManager.class), mock(BroadcastService.class), mock(NotificationManager.class),
                mock(CacheManager.class), senders, blockState);
        device = new Device();
        device.setId(31);
        when(storage.getObject(eq(Device.class), any())).thenReturn(device);
        command = new Command();
        command.setDeviceId(31);
        command.setType(Command.TYPE_ENGINE_STOP);
        when(blockState.request(command)).thenReturn("request-id");
    }

    @Test
    public void offlineRequestIsRecordedBeforeQueueing() throws Exception {
        when(storage.addObject(any(QueuedCommand.class), any())).thenReturn(12L);
        QueuedCommand queued = manager.sendCommand(command);
        assertEquals(12, queued.getId());
        assertEquals(Command.TYPE_ENGINE_STOP, queued.getType());
        var order = inOrder(blockState, storage);
        order.verify(blockState).request(command);
        order.verify(storage).addObject(any(QueuedCommand.class), any());
        verify(blockState, never()).failed(anyLong(), any());
    }

    @Test
    public void successfulSendDoesNotConfirmState() throws Exception {
        var sender = mock(CommandSender.class);
        when(senders.getSender(device)).thenReturn(sender);
        assertNull(manager.sendCommand(command));
        verify(sender).sendCommand(device, command);
        verify(blockState).request(command);
        verifyNoMoreInteractions(blockState);
    }

    @Test
    public void dispatchFailureIsRecordedAndOriginalErrorIsPreserved() throws Exception {
        var sender = mock(CommandSender.class);
        when(senders.getSender(device)).thenReturn(sender);
        var failure = new IllegalStateException("send failed");
        doThrow(failure).when(sender).sendCommand(device, command);
        assertSame(failure, assertThrows(Exception.class, () -> manager.sendCommand(command)));
        verify(blockState).failed(31, "request-id");
    }

    @Test
    public void persistenceFailurePreventsSendingAnUntrackedRequest() throws Exception {
        when(blockState.request(command)).thenThrow(new StorageException("database unavailable"));
        assertThrows(StorageException.class, () -> manager.sendCommand(command));
        verifyNoInteractions(senders);
        verify(storage, never()).addObject(any(), any());
    }
}
