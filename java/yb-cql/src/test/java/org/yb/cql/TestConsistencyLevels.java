package org.yb.cql;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.exceptions.ProtocolError;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.google.common.net.HostAndPort;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.ColumnSchema;
import org.yb.Common;
import org.yb.Schema;
import org.yb.Type;
import org.yb.client.*;
import org.yb.consensus.Metadata;
import org.yb.minicluster.MiniYBCluster;
import org.yb.minicluster.MiniYBDaemon;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestConsistencyLevels extends BaseCQLTest {
  protected static final Logger LOG = LoggerFactory.getLogger(TestConsistencyLevels.class);

  private static final String TABLE_NAME = "test_consistency";

  private static final int NUM_ROWS = 1000;

  private static final int NUM_OPS = 100;

  private static final int WAIT_FOR_REPLICATION_TIMEOUT_MS = 10000;

  private YBTable ybTable = null;

  private LocatedTablet tablet = null;

  @Override
  public void useKeyspace() throws Exception {
    // Use the DEFAULT_KEYSPACE for this test.
  }

  @Override
  protected void afterBaseCQLTestTearDown() throws Exception {
    // We need to destroy the mini cluster since we don't want metrics from one test to interfere
    // with another.
    destroyMiniCluster();
  }

  @Before
  public void setUpTable() throws Exception {
    // Create a table using YBClient to enforce a single tablet.
    YBClient client = miniCluster.getClient();
    ColumnSchema.ColumnSchemaBuilder hash_column =
      new ColumnSchema.ColumnSchemaBuilder("h", Type.INT32);
    hash_column.hashKey(true);
    ColumnSchema.ColumnSchemaBuilder range_column =
      new ColumnSchema.ColumnSchemaBuilder("r", Type.INT32);
    range_column.rangeKey(true, ColumnSchema.SortOrder.ASC);
    ColumnSchema.ColumnSchemaBuilder regular_column =
      new ColumnSchema.ColumnSchemaBuilder("k", Type.INT32);

    CreateTableOptions options = new CreateTableOptions();
    options.setNumTablets(1);
    options.setTableType(Common.TableType.YQL_TABLE_TYPE);
    client.createTable(TABLE_NAME, new Schema(
      Arrays.asList(hash_column.build(), range_column.build(), regular_column.build())), options);

    ybTable = client.openTable(TABLE_NAME);

    // Verify number of replicas.
    List<LocatedTablet> tablets = ybTable.getTabletsLocations(0);
    assertEquals(1, tablets.size());
    tablet = tablets.get(0);

    // Insert some rows.
    for (int idx = 0; idx < NUM_ROWS; idx++) {
      // INSERT: Valid statement with column list.
      String insert_stmt = String.format(
        "INSERT INTO %s.%s(h, r, k) VALUES(%d, %d, %d);", DEFAULT_KEYSPACE, TABLE_NAME, idx, idx,
        idx);
      session.execute(insert_stmt);
    }
  }

  @Test
  public void testReadFromLeader() throws Exception {
    // Read from the leader.
    for (int i = 0; i < NUM_OPS; i++) {
      ConsistencyLevel consistencyLevel =
        (i % 2 == 0) ? ConsistencyLevel.LOCAL_ONE : ConsistencyLevel.QUORUM;
      assertTrue(verifyNumRows(consistencyLevel));
    }

    // Verify all reads went to the leader.
    Map<HostAndPort, MiniYBDaemon> tservers = miniCluster.getTabletServers();
    assertEquals(tservers.size(), tablet.getReplicas().size());
    for (LocatedTablet.Replica replica : tablet.getReplicas()) {
      String host = replica.getRpcHost();
      int webPort = tservers.get(HostAndPort.fromParts(host, replica.getRpcPort())).getWebPort();
      if (replica.getRole().equals(Metadata.RaftPeerPB.Role.LEADER.toString())) {
        assertEquals(NUM_OPS, getTServerMetric(host, webPort, TSERVER_READ_METRIC));
      } else {
        assertEquals(0, getTServerMetric(host, webPort, TSERVER_READ_METRIC));
      }
    }
  }

  private boolean verifyNumRows(ConsistencyLevel consistencyLevel) {
    Statement statement = QueryBuilder.select()
      .from(DEFAULT_KEYSPACE, TABLE_NAME)
      .setConsistencyLevel(consistencyLevel);
    return NUM_ROWS == session.execute(statement).all().size();
  }

  @Test
  public void testReadFromFollowers() throws Exception {
    // Wait for replicas to converge. Assuming 10 ops will end up hitting each tserver.
    for (int i = 0; i < 10; i++) {
      TestUtils.waitFor(() -> {
        return verifyNumRows(ConsistencyLevel.ONE);
      }, WAIT_FOR_REPLICATION_TIMEOUT_MS);
    }

    // Read from any replica.
    for (int i = 0; i < NUM_OPS; i++) {
      assertTrue(verifyNumRows(ConsistencyLevel.ONE));
    }

    // Verify reads were spread across all replicas.
    Map<HostAndPort, MiniYBDaemon> tservers = miniCluster.getTabletServers();
    long totalOps = 0;
    for (LocatedTablet.Replica replica: tablet.getReplicas()) {
      String host = replica.getRpcHost();
      int webPort = tservers.get(HostAndPort.fromParts(host, replica.getRpcPort())).getWebPort();

      long numOps = getTServerMetric(host, webPort, TSERVER_READ_METRIC);
      LOG.info("Num ops for tserver: " + replica.toString() + " : " + numOps);
      totalOps += numOps;
      // At least some ops went to each server.
      assertTrue(numOps > NUM_OPS/10);
    }
    assertTrue(totalOps >= NUM_OPS);
  }

  private void runInvalidConsistencyLevel(String TABLE_NAME, ConsistencyLevel consistencyLevel) {
    try {
      Statement statement = QueryBuilder.select()
        .from(DEFAULT_KEYSPACE, TABLE_NAME)
        .setConsistencyLevel(consistencyLevel);
      session.execute(statement);
      fail(String.format("No failure for consistency level: %d", consistencyLevel));
    } catch (ProtocolError e) {
      // expected.
    }
  }

  @Test
  public void testInvalidCQLConsistencyLevels() throws Exception {
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.ALL);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.ANY);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.EACH_QUORUM);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.LOCAL_QUORUM);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.LOCAL_SERIAL);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.SERIAL);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.THREE);
    runInvalidConsistencyLevel(TABLE_NAME, ConsistencyLevel.TWO);
  }
}