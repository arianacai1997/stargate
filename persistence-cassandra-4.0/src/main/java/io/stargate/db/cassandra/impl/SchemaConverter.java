package io.stargate.db.cassandra.impl;

import com.datastax.oss.driver.shaded.guava.common.collect.Iterables;
import io.stargate.db.datastore.common.AbstractCassandraSchemaConverter;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Column.ColumnType;
import io.stargate.db.schema.Column.Kind;
import io.stargate.db.schema.Column.Order;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.marshal.UserType;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.ViewMetadata;

public class SchemaConverter
    extends AbstractCassandraSchemaConverter<
        KeyspaceMetadata, TableMetadata, ColumnMetadata, UserType, IndexMetadata, ViewMetadata> {

  @Override
  protected String keyspaceName(KeyspaceMetadata keyspace) {
    return keyspace.name;
  }

  @Override
  protected Map<String, String> replicationOptions(KeyspaceMetadata keyspace) {
    return keyspace.params.replication.asMap();
  }

  @Override
  protected boolean usesDurableWrites(KeyspaceMetadata keyspace) {
    return keyspace.params.durableWrites;
  }

  @Override
  protected Iterable<TableMetadata> tables(KeyspaceMetadata keyspace) {
    return keyspace.tables;
  }

  @Override
  protected Iterable<UserType> userTypes(KeyspaceMetadata keyspace) {
    return keyspace.types;
  }

  @Override
  protected Iterable<ViewMetadata> views(KeyspaceMetadata keyspace) {
    return keyspace.views;
  }

  @Override
  protected String tableName(TableMetadata table) {
    return table.name;
  }

  @Override
  protected Iterable<ColumnMetadata> columns(TableMetadata table) {
    return table::allColumnsInSelectOrder;
  }

  @Override
  protected String columnName(ColumnMetadata column) {
    return column.name.toString();
  }

  @Override
  protected ColumnType columnType(ColumnMetadata column) {
    return Conversion.getTypeFromInternal(column.type);
  }

  @Override
  protected Order columnClusteringOrder(ColumnMetadata column) {
    switch (column.clusteringOrder()) {
      case ASC:
        return Column.Order.ASC;
      case DESC:
        return Column.Order.DESC;
      case NONE:
        return null;
      default:
        throw new IllegalStateException("Clustering columns should always have an order");
    }
  }

  @Override
  protected Kind columnKind(ColumnMetadata column) {
    switch (column.kind) {
      case REGULAR:
        return Column.Kind.Regular;
      case STATIC:
        return Column.Kind.Static;
      case PARTITION_KEY:
        return Column.Kind.PartitionKey;
      case CLUSTERING:
        return Column.Kind.Clustering;
    }
    throw new IllegalStateException("Unknown column kind");
  }

  @Override
  protected Iterable<IndexMetadata> secondaryIndexes(TableMetadata table) {
    // This is an unfortunate departure from the rest of this class being 'stateless'. This
    // is really a weakness of the C* schema code that some of the index information is not
    // part of schema objects and that we should reach into the ColumnFamilyStore, but there is
    // nothing we can do at the Stargate level...
    return Iterables.transform(
        Keyspace.openAndGetStore(table).indexManager.listIndexes(), Index::getIndexMetadata);
  }

  @Override
  protected String comment(TableMetadata table) {
    return table.params.comment;
  }

  @Override
  protected String indexName(IndexMetadata index) {
    return index.name;
  }

  @Override
  protected String indexTarget(IndexMetadata index) {
    return index.options.get("target");
  }

  @Override
  protected boolean isCustom(IndexMetadata index) {
    return index.kind == IndexMetadata.Kind.CUSTOM;
  }

  @Override
  protected String indexClass(IndexMetadata index) {
    return index.options.get("class_name");
  }

  @Override
  protected Map<String, String> indexOptions(IndexMetadata index) {
    return index.options.entrySet().stream()
        .filter(x -> !EXCLUDED_INDEX_OPTIONS.contains(x.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  protected List<Column> userTypeFields(UserType userType) {
    return Conversion.getUDTColumns(userType);
  }

  @Override
  protected String userTypeName(UserType userType) {
    return userType.getNameAsString();
  }

  @Override
  protected TableMetadata asTable(ViewMetadata view) {
    return view.metadata;
  }

  @Override
  protected boolean isBaseTableOf(TableMetadata table, ViewMetadata view) {
    return view.baseTableId.equals(table.id);
  }
}
