package org.traccar.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.Command;
import org.traccar.model.Position;
import org.traccar.model.QueuedCommand;

import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommandLedgerTest {

    private JdbcDataSource source;
    private CommandLedger ledger;
    private Command command;

    @BeforeEach
    public void setup() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tc_devices (id INT PRIMARY KEY)");
            statement.execute("INSERT INTO tc_devices VALUES (1),(2)");
            try (var resources = new DirectoryResourceAccessor(Path.of("schema"))) {
                new Liquibase("changelog-mobgo-command-ledger.xml", resources, new JdbcConnection(connection))
                        .update(new Contexts());
            }
        }
        ledger = new CommandLedger(source, new ObjectMapper());
        command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);
    }

    @Test
    public void replayReturnsSameRequestAndRejectsPayloadChanges() throws Exception {
        var first = ledger.begin(command, 2, "same-request");
        var replay = ledger.begin(command, 2, "same-request");
        assertTrue(first.newRequest());
        assertFalse(replay.newRequest());
        assertEquals(first.id(), replay.id());
        assertEquals(1, ledger.requests(1).size());
        command.setType(Command.TYPE_ENGINE_RESUME);
        assertThrows(CommandLedger.Conflict.class, () -> ledger.begin(command, 2, "same-request"));
    }

    @Test
    public void duplicateRelayIsBlockedButOppositeIntentIsAllowed() throws Exception {
        ledger.begin(command, 2, "first");
        assertThrows(CommandLedger.Conflict.class, () -> ledger.begin(command, 3, "other-click"));
        command.setType(Command.TYPE_ENGINE_RESUME);
        assertTrue(ledger.begin(command, 2, "unlock").newRequest());
    }

    @Test
    public void concurrentReplayCreatesOnlyOneRequest() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return ledger.begin(command, 2, "concurrent");
            });
            var second = executor.submit(() -> {
                start.await();
                return ledger.begin(command, 2, "concurrent");
            });
            start.countDown();
            int created = (first.get(10, TimeUnit.SECONDS).newRequest() ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS).newRequest() ? 1 : 0);
            assertEquals(1, created);
        }
        assertEquals(1, ledger.requests(1).size());
    }

    @Test
    public void replyIsNotPhysicalConfirmationAndIsNotFalselyCorrelated() throws Exception {
        ledger.begin(command, 2, "relay");
        Position position = new Position();
        position.setDeviceId(1);
        position.setServerTime(new Date());
        position.setDeviceTime(new Date());
        position.set(Position.KEY_RESULT, "RELAY 1 OK, executed after speed < 20km/h");
        ledger.observe(position);
        ledger.observe(position);
        assertEquals("unknown", ledger.requests(1).getFirst().reportedState());
        assertEquals(1, ledger.responses(1).size());
        assertEquals("unconfirmed", ledger.responses(1).getFirst().correlation());
        assertEquals("deferred", ledger.responses(1).getFirst().state());
        assertTrue(ledger.responses(2).isEmpty());
    }

    @Test
    public void onlyLaterTelemetryUpdatesReportedRelayState() throws Exception {
        ledger.begin(command, 2, "relay");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE tc_command_requests SET requestedat=TIMESTAMP '2000-01-01 00:00:00'");
        }
        Position position = new Position();
        position.setDeviceId(1);
        position.setServerTime(new Date());
        position.setDeviceTime(new Date(System.currentTimeMillis() - 1000));
        position.set(Position.KEY_BLOCKED, true);
        ledger.observe(position);
        assertEquals("blocked", ledger.requests(1).getFirst().reportedState());
        position.setDeviceTime(new Date(System.currentTimeMillis() - 10000));
        position.set(Position.KEY_BLOCKED, false);
        ledger.observe(position);
        assertEquals("blocked", ledger.requests(1).getFirst().reportedState());
    }

    @Test
    public void queuedReleaseAndDispatchFailureKeepDistinctEvidence() throws Exception {
        var receipt = ledger.begin(command, 2, "queued");
        QueuedCommand queued = QueuedCommand.fromCommand(command);
        queued.setId(123);
        ledger.dispatched(receipt.id(), queued);
        assertEquals("queued", ledger.requests(1).getFirst().state());
        ledger.released(123);
        assertEquals("queueReleased", ledger.requests(1).getFirst().state());
        ledger.failed(receipt.id(), new IllegalStateException("private transport details"));
        var request = ledger.requests(1).getFirst();
        assertEquals("uncertain", request.state());
        assertEquals("IllegalStateException", request.errorCode());
        assertTrue(ledger.requests(2).isEmpty());
    }
}
