package io.stargate.db.dse.impl;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import io.stargate.db.ImmutableParameters;
import io.stargate.db.Parameters;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.stargate.db.ConsistencyLevel;
import org.apache.cassandra.stargate.exceptions.RequestFailureReason;
import org.apache.cassandra.stargate.transport.ProtocolVersion;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ConversionTest extends BaseDseTest {

  private static ByteBuffer bytes(String v) {
    return UTF8Type.instance.decompose(v);
  }

  // The name is all we care about in this class, so we put the rest to random values.
  private static ColumnSpecification spec(String name) {
    ColumnIdentifier id = ColumnIdentifier.getInterned(name, true);
    return new ColumnSpecification("ks", "tbl", id, UTF8Type.instance);
  }

  @Test
  public void testAllNonDefaultQueryOptionsConversion() {

    List<ByteBuffer> values = asList(bytes("world"), bytes("hello"));
    List<String> names = asList("v2", "v1");
    // QueryOptions deserialize the paging state so we need to have something valid. We really
    // don't care otherwise, so this is the simplest paging state bytes that deserialize and
    // is equal to itself when re-serialized.
    ByteBuffer pagingState = ByteBuffer.wrap(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

    // Test a case that uses all non-default options (we skip nowInSeconds, which is a protocol v5
    // feature and is not implemented by DSE 6.8 yet).
    Parameters parameters =
        ImmutableParameters.builder()
            .consistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
            .skipMetadataInResult(true)
            .protocolVersion(ProtocolVersion.V3)
            .serialConsistencyLevel(ConsistencyLevel.LOCAL_SERIAL)
            .pageSize(15)
            .pagingState(pagingState)
            .defaultTimestamp(123456)
            .nowInSeconds(123)
            .defaultKeyspace("foobar")
            .build();

    QueryOptions converted = Conversion.toInternal(values, names, parameters);

    // We have to prepare() to check the named parameters are re-ordered properly.
    converted = converted.prepare(asList(spec("v1"), spec("v2")));

    assertThat(converted.getConsistency())
        .isEqualTo(org.apache.cassandra.db.ConsistencyLevel.LOCAL_QUORUM);
    assertThat(converted.skipMetadata()).isTrue();
    // Again, due to the names, we expect the values to have been re-ordered
    assertThat(converted.getValues()).isEqualTo(asList(bytes("hello"), bytes("world")));
    assertThat(converted.getProtocolVersion())
        .isEqualTo(org.apache.cassandra.transport.ProtocolVersion.V3);
    // Since we don't use the default, we should we good passing null
    assertThat(converted.getSerialConsistency(null))
        .isEqualTo(org.apache.cassandra.db.ConsistencyLevel.LOCAL_SERIAL);
    assertThat(converted.getPagingOptions().pageSize().isInRows()).isTrue();
    assertThat(converted.getPagingOptions().pageSize().inRows()).isEqualTo(15);
    assertThat(converted.getPagingOptions().state()).isEqualTo(pagingState);
    assertThat(converted.getTimestamp()).isEqualTo(123456);
    assertThat(converted.getKeyspace()).isEqualTo("foobar");
  }

  @Test
  public void testAllDefaultQueryOptionsConversion() {
    // Test a case that uses all non-default options.
    QueryOptions converted =
        Conversion.toInternal(Collections.emptyList(), null, Parameters.defaults());

    QueryState queryState = QueryState.forInternalCalls();

    // Using prepare to emulate real usage.
    converted = converted.prepare(Collections.emptyList());

    assertThat(converted.getConsistency()).isEqualTo(org.apache.cassandra.db.ConsistencyLevel.ONE);
    assertThat(converted.skipMetadata()).isFalse();
    // Again, due to the names, we expect the values to have been re-ordered
    assertThat(converted.getValues()).isEmpty();
    assertThat(converted.getProtocolVersion())
        .isEqualTo(Conversion.toInternal(ProtocolVersion.CURRENT));
    assertThat(converted.getSerialConsistency(queryState))
        .isEqualTo(org.apache.cassandra.db.ConsistencyLevel.SERIAL);
    assertThat(converted.getPagingOptions()).isNull();
    // The timestamp is going to be basically the server time. We don't care about the details,
    // let's just make sure it's not obviously broken
    assertThat(converted.getTimestamp()).isGreaterThan(0);
    assertThat(converted.getKeyspace()).isNull();
  }

  @Nested
  class RequestFailureReasons {
    private RequestFailureReason convert(
        org.apache.cassandra.exceptions.RequestFailureReason internal) {
      return Conversion.toExternal(
              Collections.singletonMap(InetAddress.getLoopbackAddress(), internal))
          .values()
          .iterator()
          .next();
    }

    @Test
    void unknown() {
      assertThat(convert(org.apache.cassandra.exceptions.RequestFailureReason.UNKNOWN))
          .isEqualTo(RequestFailureReason.UNKNOWN);
    }

    @Test
    void readTooManyTombstones() {
      assertThat(
              convert(
                  org.apache.cassandra.exceptions.RequestFailureReason.READ_TOO_MANY_TOMBSTONES))
          .isEqualTo(RequestFailureReason.READ_TOO_MANY_TOMBSTONES);
    }

    @Test
    void allKnownCodes() {
      for (org.apache.cassandra.exceptions.RequestFailureReason r :
          org.apache.cassandra.exceptions.RequestFailureReason.values()) {
        if (r == org.apache.cassandra.exceptions.RequestFailureReason.UNKNOWN) {
          continue;
        }

        assertThat(convert(r))
            .withFailMessage(() -> "" + r + " should not convert to UNKNOWN")
            .isNotEqualTo(RequestFailureReason.UNKNOWN);
      }
    }
  }
}
