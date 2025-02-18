/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.graphql.schema.graphqlfirst.processor;

import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableMap;
import graphql.Scalars;
import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.ImmutableUserDefinedType;
import io.stargate.db.schema.ParameterizedType;
import io.stargate.db.schema.UserDefinedType;
import io.stargate.graphql.schema.scalars.CqlScalar;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class FieldModelBuilder extends ModelBuilderBase<FieldModel> {

  private static final Map<String, Column.ColumnType> GRAPHQL_SCALAR_MAPPINGS =
      ImmutableMap.<String, Column.ColumnType>builder()
          .put(Scalars.GraphQLInt.getName(), Column.Type.Int)
          .put(Scalars.GraphQLFloat.getName(), Column.Type.Double)
          .put(Scalars.GraphQLString.getName(), Column.Type.Varchar)
          .put(Scalars.GraphQLBoolean.getName(), Column.Type.Boolean)
          .put(Scalars.GraphQLID.getName(), Column.Type.Uuid)
          .build();

  private final FieldDefinition field;
  private final String parentCqlName;
  private final EntityModel.Target targetContainer;
  private final boolean checkForInputType;
  private final String graphqlName;
  private final Type<?> graphqlType;
  private final String messagePrefix;
  private final Optional<Directive> cqlColumnDirective;

  FieldModelBuilder(
      FieldDefinition field,
      ProcessingContext context,
      String parentCqlName,
      String parentGraphqlName,
      EntityModel.Target targetContainer,
      boolean checkForInputType) {

    super(context, field.getSourceLocation());

    this.field = field;
    this.parentCqlName = parentCqlName;
    this.targetContainer = targetContainer;
    this.checkForInputType = checkForInputType;

    this.graphqlName = field.getName();
    this.graphqlType = field.getType();
    this.messagePrefix = parentGraphqlName + "." + graphqlName;
    this.cqlColumnDirective = DirectiveHelper.getDirective(CqlDirectives.COLUMN, field);
  }

  @Override
  FieldModel build() throws SkipException {
    String cqlName =
        cqlColumnDirective
            .flatMap(d -> DirectiveHelper.getStringArgument(d, CqlDirectives.COLUMN_NAME, context))
            .orElse(graphqlName);
    boolean isUdtField = targetContainer == EntityModel.Target.UDT;
    boolean partitionKey = isPartitionKey(isUdtField);
    Optional<Column.Order> clusteringOrder = getClusteringOrder(isUdtField);

    if (partitionKey && clusteringOrder.isPresent()) {
      invalidMapping("%s: can't be both a partition key and a clustering key.", messagePrefix);
      throw SkipException.INSTANCE;
    }

    boolean isPk = partitionKey || clusteringOrder.isPresent();

    Column.ColumnType cqlType = inferCqlType(graphqlType, context);
    if (isPk || (cqlType.isUserDefined() && isUdtField)) {
      cqlType = cqlType.frozen();
    }

    cqlType = maybeUseTypeHint(cqlType, isPk, isUdtField);

    return new FieldModel(
        graphqlName,
        graphqlType,
        cqlName,
        cqlType,
        partitionKey,
        clusteringOrder,
        getIndex(cqlName, isUdtField, isPk, cqlType));
  }

  private Boolean isPartitionKey(boolean isUdtField) {
    return cqlColumnDirective
        .flatMap(
            d -> DirectiveHelper.getBooleanArgument(d, CqlDirectives.COLUMN_PARTITION_KEY, context))
        .filter(
            __ -> {
              if (isUdtField) {
                warn(
                    "%s: UDT fields should not be marked as partition keys (this will be ignored)",
                    messagePrefix);
                return false;
              }
              return true;
            })
        .orElse(false);
  }

  private Optional<Column.Order> getClusteringOrder(boolean isUdtField) {
    return cqlColumnDirective
        .flatMap(
            d ->
                DirectiveHelper.getEnumArgument(
                    d, CqlDirectives.COLUMN_CLUSTERING_ORDER, Column.Order.class, context))
        .filter(
            __ -> {
              if (isUdtField) {
                warn(
                    "%s: UDT fields should not be marked as clustering keys (this will be ignored)",
                    messagePrefix);
                return false;
              }
              return true;
            });
  }

  /** Computes the CQL type that corresponds to the GraphQL type of a field. */
  private Column.ColumnType inferCqlType(Type<?> graphqlType, ProcessingContext context)
      throws SkipException {
    if (graphqlType instanceof NonNullType) {
      return inferCqlType(((NonNullType) graphqlType).getType(), context);
    } else if (graphqlType instanceof ListType) {
      return Column.Type.List.of(inferCqlType(((ListType) graphqlType).getType(), context));
    } else {
      String typeName = ((TypeName) graphqlType).getName();
      TypeDefinitionRegistry typeRegistry = context.getTypeRegistry();

      // Check built-in GraphQL scalars
      if (GRAPHQL_SCALAR_MAPPINGS.containsKey(typeName)) {
        return GRAPHQL_SCALAR_MAPPINGS.get(typeName);
      }

      // Otherwise, check if the type references another definition in the user's schema
      if (typeRegistry.types().containsKey(typeName)) {
        TypeDefinition<?> definition =
            typeRegistry.getType(typeName).orElseThrow(AssertionError::new);
        if (definition instanceof EnumTypeDefinition) {
          return Column.Type.Varchar;
        }
        if (definition instanceof ObjectTypeDefinition) {
          return expectUdt((ObjectTypeDefinition) definition);
        }
        invalidMapping("%s: can't map type '%s' to CQL", messagePrefix, graphqlType);
        throw SkipException.INSTANCE;
      }

      // Otherwise, check our own CQL scalars
      // Note that we do this last in case the user defined an object or enum type that happens to
      // have the same name as a CQL scalar.
      Optional<CqlScalar> maybeCqlScalar = CqlScalar.fromGraphqlName(typeName);
      if (maybeCqlScalar.isPresent()) {
        CqlScalar cqlScalar = maybeCqlScalar.get();
        // Remember that we'll need to add the scalar to the RuntimeWiring
        context.getUsedCqlScalars().add(cqlScalar);
        return cqlScalar.getCqlType();
      }

      invalidMapping("%s: can't map type '%s' to CQL", messagePrefix, graphqlType);
      throw SkipException.INSTANCE;
    }
  }

  /** Checks that if a field is an object type, then that object maps to a UDT. */
  private Column.ColumnType expectUdt(ObjectTypeDefinition definition) throws SkipException {
    boolean isUdt =
        DirectiveHelper.getDirective(CqlDirectives.ENTITY, definition)
            .flatMap(
                d ->
                    DirectiveHelper.getEnumArgument(
                        d, CqlDirectives.ENTITY_TARGET, EntityModel.Target.class, context))
            .filter(target -> target == EntityModel.Target.UDT)
            .isPresent();
    if (isUdt) {
      if (checkForInputType
          && !DirectiveHelper.getDirective(CqlDirectives.INPUT, definition).isPresent()) {
        invalidMapping(
            "%s: type '%s' must also be annotated with @%s",
            messagePrefix, definition.getName(), CqlDirectives.INPUT);
        throw SkipException.INSTANCE;
      }
      return ImmutableUserDefinedType.builder()
          .keyspace(context.getKeyspace().name())
          .name(definition.getName())
          .build();
    }

    invalidMapping(
        "%s: can't map type '%s' to CQL -- "
            + "if a field references an object type, then that object should map to a UDT",
        messagePrefix, definition.getName());
    throw SkipException.INSTANCE;
  }

  /**
   * If the directive provides a CQL type hint, use it, provided that it is valid and compatible
   * with the inferred type.
   */
  private Column.ColumnType maybeUseTypeHint(
      Column.ColumnType cqlType, boolean isPk, boolean isUdtField) throws SkipException {
    Optional<String> maybeCqlTypeHint =
        cqlColumnDirective.flatMap(
            d -> DirectiveHelper.getStringArgument(d, CqlDirectives.COLUMN_TYPE_HINT, context));
    if (!maybeCqlTypeHint.isPresent()) {
      return cqlType;
    }

    Column.ColumnType cqlTypeHint;
    String spec = maybeCqlTypeHint.get();
    try {
      cqlTypeHint = Column.Type.fromCqlDefinitionOf(context.getKeyspace(), spec, false);
    } catch (IllegalArgumentException e) {
      invalidSyntax("%s: could not parse CQL type '%s': %s", messagePrefix, spec, e.getMessage());
      throw SkipException.INSTANCE;
    }
    if (isPk
        && (cqlTypeHint.isParameterized() || cqlTypeHint.isUserDefined())
        && !cqlTypeHint.isFrozen()) {
      invalidMapping(
          "%s: invalid type hint '%s' -- partition or clustering columns must be frozen",
          messagePrefix, spec);
      throw SkipException.INSTANCE;
    }
    if (cqlTypeHint.isUserDefined() && isUdtField && !cqlTypeHint.isFrozen()) {
      invalidMapping(
          "%s: invalid type hint '%s' -- nested UDTs must be frozen", messagePrefix, spec);
      throw SkipException.INSTANCE;
    }
    if (!isCompatible(cqlTypeHint, cqlType)) {
      invalidMapping(
          "%s: invalid type hint '%s'-- the inferred type was '%s', you can only change "
              + "frozenness or use sets instead of lists",
          messagePrefix, cqlTypeHint.cqlDefinition(), cqlType.cqlDefinition());
      throw SkipException.INSTANCE;
    }
    return cqlTypeHint;
  }

  /** Checks if the only differences are frozen vs. not frozen, or list vs. set. */
  private static boolean isCompatible(Column.ColumnType type1, Column.ColumnType type2) {
    Column.Type raw1 = turnSetIntoList(type1.rawType());
    Column.Type raw2 = turnSetIntoList(type2.rawType());
    if (!Objects.equals(raw1, raw2)) {
      return false;
    }
    if (raw2 == Column.Type.UDT) {
      // Only compare the coordinates, the types might be shallow
      UserDefinedType udt1 = (UserDefinedType) type1;
      UserDefinedType udt2 = (UserDefinedType) type2;
      return Objects.equals(udt1.keyspace(), udt2.keyspace())
          && Objects.equals(udt1.name(), udt2.name());
    }
    if (raw2.isParameterized()) {
      ParameterizedType parameterized1 = (ParameterizedType) type1;
      ParameterizedType parameterized2 = (ParameterizedType) type2;
      if (parameterized1.parameters().size() != parameterized2.parameters().size()) {
        return false;
      }
      for (int i = 0; i < parameterized1.parameters().size(); i++) {
        Column.ColumnType sub1 = parameterized1.parameters().get(i);
        Column.ColumnType sub2 = parameterized2.parameters().get(i);
        if (!isCompatible(sub1, sub2)) {
          return false;
        }
      }
    }
    return true;
  }

  private static Column.Type turnSetIntoList(Column.Type type) {
    return (type == Column.Type.Set) ? Column.Type.List : type;
  }

  private Optional<IndexModel> getIndex(
      String cqlName, boolean isUdtField, boolean isPk, final Column.ColumnType cqlType)
      throws SkipException {
    Optional<Directive> cqlIndexDirective =
        DirectiveHelper.getDirective(CqlDirectives.INDEX, field);
    if (cqlIndexDirective.isPresent()) {
      if (isPk) {
        invalidMapping("%s: partition or clustering columns can't have an index", messagePrefix);
        throw SkipException.INSTANCE;
      }
      if (isUdtField) {
        invalidMapping("%s: UDT fields can't have an index", messagePrefix);
        throw SkipException.INSTANCE;
      }
      return Optional.of(
          new IndexModelBuilder(
                  cqlIndexDirective.get(), parentCqlName, cqlName, cqlType, messagePrefix, context)
              .build());
    }
    return Optional.empty();
  }
}
