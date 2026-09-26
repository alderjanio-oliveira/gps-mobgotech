package org.traccar.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.nio.file.Path;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class BlockStateManagerTest {

    private Storage storage;
    private BlockStateManager manager;
    private CacheManager cache;

    @BeforeEach
    public void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tc_devices (id BIGINT PRIMARY KEY, name VARCHAR(128))");
            statement.execute("INSERT INTO tc_devices (id, name) VALUES (31, 'Tracker')");
            try (var resources = new DirectoryResourceAccessor(Path.of("schema"));
                    var liquibase = new Liquibase("changelog-block-state.xml", resources,
                            new JdbcConnection(connection))) {
                liquibase.update(new Contexts());
                // The migration must be safe to run again during subsequent startups.
                liquibase.update(new Contexts());
            }
        }
        storage = new DatabaseStorage(new Config(), source, new ObjectMapper());
        cache = mock(CacheManager.class);
        manager = new BlockStateManager(storage, cache);
    }

    private Device device() throws Exception {
        return storage.getObject(Device.class, new Request(new Columns.All(), new Condition.Equals("id", 31L)));
    }

    private Command command(String type) {
        Command command = new Command();
        command.setDeviceId(31);
        command.setType(type);
        return command;
    }

    private Position position(Object blocked, long time) {
        Position position = new Position();
        position.setDeviceId(31);
        position.setDeviceTime(new Date(time));
        position.setServerTime(new Date(Math.max(System.currentTimeMillis(), time)));
        if (blocked != null) {
            position.getAttributes().put(Position.KEY_BLOCKED, blocked);
        }
        return position;
    }

    @Test
    public void unknownUntilTrackerReportsAndSurvivesRestart() throws Exception {
        assertEquals("unknown", device().getBlockState());
        assertNull(device().getBlockStateTime());
        manager.request(command(Command.TYPE_ENGINE_STOP));
        assertEquals("unknown", device().getBlockState());
        assertEquals("pending", device().getBlockRequestStatus());
        long time = device().getBlockRequestedTime().getTime() + 1000;
        manager.report(position(true, time));
        manager = new BlockStateManager(storage, cache);
        manager.report(position(null, time + 1000));
        assertEquals("blocked", device().getBlockState());
        assertEquals(new Date(time), device().getBlockStateTime());
        assertEquals("confirmed", device().getBlockRequestStatus());
    }

    @Test
    public void explicitFalseUpdatesButOldAndMissingReportsDoNot() throws Exception {
        manager.report(position(true, 1000));
        manager.report(position(false, 3000));
        manager.report(position(true, 2000));
        manager.report(position(true, 3000));
        manager.report(position(null, 4000));
        assertEquals("unblocked", device().getBlockState());
        assertEquals(new Date(3000), device().getBlockStateTime());
    }

    @Test
    public void normalizesNumericStatusAndIgnoresInvalidOrArchivedStatus() throws Exception {
        manager.report(position(1, 1000));
        assertEquals("blocked", device().getBlockState());
        manager.report(position(0, 2000));
        manager.report(position(2, 3000));
        manager.report(position("invalid", 4000));
        Position archived = position(true, 5000);
        archived.set(Position.KEY_ARCHIVE, true);
        manager.report(archived);
        assertEquals("unblocked", device().getBlockState());
        assertEquals(new Date(2000), device().getBlockStateTime());
    }

    @Test
    public void oldOrOppositeStatusDoesNotConfirmRequest() throws Exception {
        manager.request(command(Command.TYPE_ENGINE_STOP));
        long time = device().getBlockRequestedTime().getTime();
        manager.report(position(true, time - 1000));
        assertEquals("pending", device().getBlockRequestStatus());
        manager.report(position(false, time + 1000));
        assertEquals("unblocked", device().getBlockState());
        assertEquals("pending", device().getBlockRequestStatus());
        manager.report(position(true, time + 2000));
        assertEquals("confirmed", device().getBlockRequestStatus());
    }

    @Test
    public void failedOldRequestCannotOverwriteNewRequest() throws Exception {
        String first = manager.request(command(Command.TYPE_ENGINE_STOP));
        String second = manager.request(command(Command.TYPE_ENGINE_RESUME));
        manager.failed(31, first);
        assertEquals("unblocked", device().getBlockRequestedState());
        assertEquals("pending", device().getBlockRequestStatus());
        manager.failed(31, second);
        assertEquals("failed", device().getBlockRequestStatus());
        assertEquals("unknown", device().getBlockState());
    }

    @Test
    public void rejectsFutureTimeAndUsesReceiveTimeForStatusWithoutGpsTime() throws Exception {
        Position future = position(true, 5000);
        future.setServerTime(new Date(4000));
        manager.report(future);
        assertEquals("unknown", device().getBlockState());
        Position status = position(false, 1000);
        status.setDeviceTime(null);
        status.setServerTime(new Date(4321));
        manager.report(status);
        assertEquals("unblocked", device().getBlockState());
        assertEquals(new Date(4000), device().getBlockStateTime());
    }

    @Test
    public void unrelatedCommandsDoNotAffectState() throws Exception {
        assertNull(manager.request(command(Command.TYPE_CUSTOM)));
        assertNull(device().getBlockRequestedState());
        assertNull(device().getBlockRequestStatus());
    }

    @Test
    public void userEditsCannotForgeOrEraseState() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Device edit = mapper.readValue("""
                {"name":"Updated", "blockState":"blocked", "blockRequestStatus":"confirmed",
                 "blockRequestId":"forged"}
                """, Device.class);
        assertEquals("unknown", edit.getBlockState());
        assertNull(edit.getBlockRequestStatus());
        assertNull(edit.getBlockRequestId());
        manager.report(position(true, 1000));
        assertFalse(new Columns.Exclude("id").getColumns(Device.class, "get").contains("blockState"));
        storage.updateObject(edit, new Request(new Columns.Include("name"), new Condition.Equals("id", 31L)));
        assertEquals("blocked", device().getBlockState());
        assertFalse(mapper.writeValueAsString(device()).contains("blockRequestId"));
    }
}
