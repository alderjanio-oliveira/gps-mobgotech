package org.traccar.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.h2.jdbcx.JdbcDataSource;
import org.traccar.config.Config;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

public class DatabaseFixture implements AutoCloseable {

    private final JdbcDataSource source = new JdbcDataSource();
    private final Storage storage;
    private final Connection keeper;

    public DatabaseFixture() throws Exception {
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        keeper = source.getConnection();
        try (var resources = new DirectoryResourceAccessor(Path.of("schema"))) {
            new Liquibase("changelog-master.xml", resources, new JdbcConnection(keeper)).update(new Contexts());
        }
        keeper.setAutoCommit(true);
        storage = new DatabaseStorage(new Config(), source, new ObjectMapper());
    }

    public Storage getStorage() {
        return storage;
    }

    public void sql(String sql) throws Exception {
        try (var statement = keeper.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public void close() throws Exception {
        keeper.close();
    }
}
