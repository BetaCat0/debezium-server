/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.redis;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.connector.mysql.strategy.AbstractConnectorConnection;
import io.debezium.doc.FixFor;
import io.debezium.relational.history.AbstractSchemaHistoryTest;
import io.debezium.relational.history.SchemaHistory;
import io.debezium.relational.history.SchemaHistoryMetrics;
import io.debezium.testing.testcontainers.MySqlTestResourceLifecycleManager;
import io.debezium.util.Testing;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;

import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Integration test that verifies reading and writing database schema history from Redis key value store
 *
 * @author Oren Elias
 */
@QuarkusIntegrationTest
@TestProfile(RedisSchemaHistoryTestProfile.class)
@QuarkusTestResource(RedisTestResourceLifecycleManager.class)
public class RedisSchemaHistoryIT extends AbstractSchemaHistoryTest {

    private static final String STREAM_NAME = "metadata:debezium:schema_history";
    private static final int INIT_HISTORY_SIZE = 16; // Initial number of entries in the schema history stream.

    protected static Jedis jedis;

    @Override
    @BeforeEach
    public void beforeEach() {
        super.beforeEach();
    }

    @Override
    protected SchemaHistory createHistory() {
        SchemaHistory history = new RedisSchemaHistory();

        history.configure(Configuration.create()
                .with("schema.history.internal.redis.address", HostAndPort.from(RedisTestResourceLifecycleManager.getRedisContainerAddress()))
                .build(), null, SchemaHistoryMetrics.NOOP, true);
        history.start();
        return history;
    }

    @Test
    @FixFor("DBZ-4771")
    public void testSchemaHistoryIsSaved() {
        jedis = new Jedis(HostAndPort.from(RedisTestResourceLifecycleManager.getRedisContainerAddress()));
        TestUtils.awaitStreamLengthGte(jedis, STREAM_NAME, INIT_HISTORY_SIZE);

        final List<StreamEntry> entries = jedis.xrange(STREAM_NAME, (StreamEntryID) null, (StreamEntryID) null);
        // If the test is run alone, number of entries in schema history is INIT_HISTORY_SIZE.
        // If the whole test case is run, number of entries is INIT_HISTORY_SIZE + 1 as the is one more entry from testRedisConnectionRetry test.
        assertThat(entries.size()).isIn(INIT_HISTORY_SIZE, INIT_HISTORY_SIZE + 1);
        assertTrue(entries.stream().anyMatch(item -> item.getFields().get("schema").contains("CREATE TABLE `customers`")));
    }

    @Test
    @FixFor("DBZ-4771")
    public void shouldRecordChangesAndRecoverToVariousPoints() {
        super.shouldRecordChangesAndRecoverToVariousPoints();
    }

    /**
    * Test retry mechanism when encountering Redis connectivity issues:
    * 1. Make Redis unavailable while the server is up
    * 2. Create a new table named redis_test in MySQL
    * 3. Bring Redis up again and make sure the database schema  has been written successfully
    */
    @Test
    @FixFor("DBZ-4509")
    public void testRedisConnectionRetry() throws Exception {
        Testing.Print.enable();

        Jedis jedis = new Jedis(HostAndPort.from(RedisTestResourceLifecycleManager.getRedisContainerAddress()));
        // wait until the db schema history is written for the first time
        TestUtils.awaitStreamLengthGte(jedis, STREAM_NAME, 1);

        // pause container
        Testing.print("Pausing container");
        RedisTestResourceLifecycleManager.pause();

        final AbstractConnectorConnection connection = getMySqlConnection();
        connection.connect();
        Testing.print("Creating new redis_test table and inserting 5 records to it");
        connection.execute("CREATE TABLE IF NOT EXISTS inventory.redis_test (id INT PRIMARY KEY)");
        connection.close();

        Testing.print("Sleeping for 2 seconds to flush records");
        Thread.sleep(2000);
        Testing.print("Unpausing container");
        RedisTestResourceLifecycleManager.unpause();

        // wait until the db schema history is written for the first time
        TestUtils.awaitStreamLengthGte(jedis, STREAM_NAME, INIT_HISTORY_SIZE + 1);

        final List<StreamEntry> entries = jedis.xrange(STREAM_NAME, (StreamEntryID) null, (StreamEntryID) null);

        assertEquals(INIT_HISTORY_SIZE + 1, entries.size());
        assertTrue(entries.get(INIT_HISTORY_SIZE).getFields().get("schema").contains("redis_test"));
    }

    private AbstractConnectorConnection getMySqlConnection() {
        final Configuration config = Configuration.create()
                .with("database.user", MySqlTestResourceLifecycleManager.PRIVILEGED_USER)
                .with("database.password", MySqlTestResourceLifecycleManager.PRIVILEGED_PASSWORD)
                .with("database.dbname", MySqlTestResourceLifecycleManager.DBNAME)
                .with("database.hostname", MySqlTestResourceLifecycleManager.HOST)
                .with("database.port", MySqlTestResourceLifecycleManager.getContainer().getMappedPort(MySqlTestResourceLifecycleManager.PORT))
                .build();
        return new MySqlConnectorConfig(config).getConnectorAdapter().createConnection(config);
    }
}
