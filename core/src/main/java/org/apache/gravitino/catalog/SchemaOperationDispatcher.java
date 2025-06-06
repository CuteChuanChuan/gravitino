/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.catalog;

import static org.apache.gravitino.Entity.EntityType.SCHEMA;
import static org.apache.gravitino.catalog.PropertiesMetadataHelpers.validatePropertyForCreate;
import static org.apache.gravitino.utils.NameIdentifierUtil.getCatalogIdentifier;

import java.time.Instant;
import java.util.Map;
import org.apache.gravitino.EntityAlreadyExistsException;
import org.apache.gravitino.EntityStore;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.Schema;
import org.apache.gravitino.SchemaChange;
import org.apache.gravitino.StringIdentifier;
import org.apache.gravitino.connector.HasPropertyMetadata;
import org.apache.gravitino.connector.capability.Capability;
import org.apache.gravitino.exceptions.NoSuchCatalogException;
import org.apache.gravitino.exceptions.NoSuchEntityException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.exceptions.SchemaAlreadyExistsException;
import org.apache.gravitino.lock.LockType;
import org.apache.gravitino.lock.TreeLockUtils;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.SchemaEntity;
import org.apache.gravitino.storage.IdGenerator;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaOperationDispatcher extends OperationDispatcher implements SchemaDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaOperationDispatcher.class);

  /**
   * Creates a new SchemaOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for schema operations.
   * @param store The EntityStore instance to be used for schema operations.
   * @param idGenerator The IdGenerator instance to be used for schema operations.
   */
  public SchemaOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  /**
   * Lists the schemas within the specified namespace.
   *
   * @param namespace The namespace in which to list schemas.
   * @return An array of NameIdentifier objects representing the schemas within the specified
   *     namespace.
   * @throws NoSuchCatalogException If the catalog namespace does not exist.
   */
  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) throws NoSuchCatalogException {
    return TreeLockUtils.doWithTreeLock(
        NameIdentifier.of(namespace.levels()),
        LockType.READ,
        () ->
            doWithCatalog(
                getCatalogIdentifier(NameIdentifier.of(namespace.levels())),
                c -> c.doWithSchemaOps(s -> s.listSchemas(namespace)),
                NoSuchCatalogException.class));
  }

  /**
   * Creates a new schema.
   *
   * @param ident The identifier for the schema to be created.
   * @param comment The comment for the new schema.
   * @param properties Additional properties for the new schema.
   * @return The created Schema object.
   * @throws NoSuchCatalogException If the catalog corresponding to the provided identifier does not
   *     exist.
   * @throws SchemaAlreadyExistsException If a schema with the same identifier already exists.
   */
  @Override
  public Schema createSchema(NameIdentifier ident, String comment, Map<String, String> properties)
      throws NoSuchCatalogException, SchemaAlreadyExistsException {
    NameIdentifier catalogIdent = getCatalogIdentifier(ident);

    doWithCatalog(
        catalogIdent,
        c ->
            c.doWithPropertiesMeta(
                p -> {
                  validatePropertyForCreate(p.schemaPropertiesMetadata(), properties);
                  return null;
                }),
        IllegalArgumentException.class);
    long uid = idGenerator.nextId();
    // Add StringIdentifier to the properties, the specific catalog will handle this
    // StringIdentifier to make sure only when the operation is successful, the related
    // SchemaEntity will be visible.
    StringIdentifier stringId = StringIdentifier.fromId(uid);
    Map<String, String> updatedProperties =
        StringIdentifier.newPropertiesWithId(stringId, properties);

    return TreeLockUtils.doWithTreeLock(
        catalogIdent,
        LockType.WRITE,
        () -> {
          // we do not retrieve the schema again (to obtain some values generated by underlying
          // catalog)
          // since some catalogs' API is async and the schema may not be created immediately
          Schema schema =
              doWithCatalog(
                  catalogIdent,
                  c -> c.doWithSchemaOps(s -> s.createSchema(ident, comment, updatedProperties)),
                  NoSuchCatalogException.class,
                  SchemaAlreadyExistsException.class);

          // If the Schema is maintained by the Gravitino's store, we don't have to store again.
          boolean isManagedSchema = isManagedEntity(catalogIdent, Capability.Scope.SCHEMA);
          if (isManagedSchema) {
            return EntityCombinedSchema.of(schema)
                .withHiddenProperties(
                    getHiddenPropertyNames(
                        catalogIdent,
                        HasPropertyMetadata::schemaPropertiesMetadata,
                        schema.properties()));
          }

          SchemaEntity schemaEntity =
              SchemaEntity.builder()
                  .withId(uid)
                  .withName(ident.name())
                  .withNamespace(ident.namespace())
                  .withAuditInfo(
                      AuditInfo.builder()
                          .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                          .withCreateTime(Instant.now())
                          .build())
                  .build();

          try {
            store.put(schemaEntity, true /* overwrite */);
          } catch (Exception e) {
            LOG.error(FormattedErrorMessages.STORE_OP_FAILURE, "put", ident, e);
            return EntityCombinedSchema.of(schema)
                .withHiddenProperties(
                    getHiddenPropertyNames(
                        catalogIdent,
                        HasPropertyMetadata::schemaPropertiesMetadata,
                        schema.properties()));
          }

          // Merge both the metadata from catalog operation and the metadata from entity store.
          return EntityCombinedSchema.of(schema, schemaEntity)
              .withHiddenProperties(
                  getHiddenPropertyNames(
                      catalogIdent,
                      HasPropertyMetadata::schemaPropertiesMetadata,
                      schema.properties()));
        });
  }

  /**
   * Loads and retrieves a schema.
   *
   * @param ident The identifier of the schema to be loaded.
   * @return The loaded Schema object.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  @Override
  public Schema loadSchema(NameIdentifier ident) throws NoSuchSchemaException {
    // Load the schema and check if this schema is already imported.
    EntityCombinedSchema schema =
        TreeLockUtils.doWithTreeLock(ident, LockType.READ, () -> internalLoadSchema(ident));

    if (!schema.imported()) {
      TreeLockUtils.doWithTreeLock(
          NameIdentifier.of(ident.namespace().levels()),
          LockType.WRITE,
          () -> {
            importSchema(ident);
            return null;
          });
    }

    return schema;
  }

  /**
   * Alters the schema by applying the provided schema changes.
   *
   * @param ident The identifier of the schema to be altered.
   * @param changes The array of SchemaChange objects representing the alterations to apply.
   * @return The altered Schema object.
   * @throws NoSuchSchemaException If the schema corresponding to the provided identifier does not
   *     exist.
   */
  @Override
  public Schema alterSchema(NameIdentifier ident, SchemaChange... changes)
      throws NoSuchSchemaException {

    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    // Gravitino does not support alter schema currently, so we do not need to check whether there
    // exists SchemaChange.renameSchema in the changes and can lock schema directly.
    return TreeLockUtils.doWithTreeLock(
        ident,
        LockType.WRITE,
        () -> {
          validateAlterProperties(ident, HasPropertyMetadata::schemaPropertiesMetadata, changes);
          Schema alteredSchema =
              doWithCatalog(
                  catalogIdent,
                  c -> c.doWithSchemaOps(s -> s.alterSchema(ident, changes)),
                  NoSuchSchemaException.class);

          // If the Schema is maintained by the Gravitino's store, we don't have to alter again.
          boolean isManagedSchema = isManagedEntity(catalogIdent, Capability.Scope.SCHEMA);
          if (isManagedSchema) {
            return EntityCombinedSchema.of(alteredSchema)
                .withHiddenProperties(
                    getHiddenPropertyNames(
                        catalogIdent,
                        HasPropertyMetadata::schemaPropertiesMetadata,
                        alteredSchema.properties()));
          }

          StringIdentifier stringId = getStringIdFromProperties(alteredSchema.properties());
          // Case 1: The schema is not created by Gravitino and this schema is never imported.
          SchemaEntity se = null;
          if (stringId == null) {
            se = getEntity(ident, SCHEMA, SchemaEntity.class);
            if (se == null) {
              return EntityCombinedSchema.of(alteredSchema)
                  .withHiddenProperties(
                      getHiddenPropertyNames(
                          catalogIdent,
                          HasPropertyMetadata::schemaPropertiesMetadata,
                          alteredSchema.properties()));
            }
          }

          long schemaId;
          if (stringId != null) {
            schemaId = stringId.id();
          } else {
            schemaId = se.id();
          }

          SchemaEntity updatedSchemaEntity =
              operateOnEntity(
                  ident,
                  id ->
                      store.update(
                          id,
                          SchemaEntity.class,
                          SCHEMA,
                          schemaEntity ->
                              SchemaEntity.builder()
                                  .withId(schemaEntity.id())
                                  .withName(schemaEntity.name())
                                  .withNamespace(ident.namespace())
                                  .withAuditInfo(
                                      AuditInfo.builder()
                                          .withCreator(schemaEntity.auditInfo().creator())
                                          .withCreateTime(schemaEntity.auditInfo().createTime())
                                          .withLastModifier(
                                              PrincipalUtils.getCurrentPrincipal().getName())
                                          .withLastModifiedTime(Instant.now())
                                          .build())
                                  .build()),
                  "UPDATE",
                  schemaId);

          return EntityCombinedSchema.of(alteredSchema, updatedSchemaEntity)
              .withHiddenProperties(
                  getHiddenPropertyNames(
                      catalogIdent,
                      HasPropertyMetadata::schemaPropertiesMetadata,
                      alteredSchema.properties()));
        });
  }

  /**
   * Drops a schema.
   *
   * @param ident The identifier of the schema to be dropped.
   * @param cascade If true, drops all tables within the schema as well.
   * @return True if the schema was successfully dropped, false if the schema doesn't exist.
   * @throws NonEmptySchemaException If the schema contains tables and cascade is set to false.
   * @throws RuntimeException If an error occurs while dropping the schema.
   */
  @Override
  public boolean dropSchema(NameIdentifier ident, boolean cascade) throws NonEmptySchemaException {
    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    return TreeLockUtils.doWithTreeLock(
        catalogIdent,
        LockType.WRITE,
        () -> {
          boolean droppedFromCatalog =
              doWithCatalog(
                  catalogIdent,
                  c -> c.doWithSchemaOps(s -> s.dropSchema(ident, cascade)),
                  NonEmptySchemaException.class,
                  RuntimeException.class);

          // For managed schema, we don't need to drop the schema from the store again.
          boolean isManagedSchema = isManagedEntity(catalogIdent, Capability.Scope.SCHEMA);
          if (isManagedSchema) {
            return droppedFromCatalog;
          }

          // For the unmanaged schema, it could happen that the schema:
          // 1. It's not found in the catalog (dropped directly from underlying sources)
          // 2. It's found in the catalog but not in the store (not managed by Gravitino)
          // 3. It's found in the catalog and the store (managed by Gravitino)
          // 4. Neither found in the catalog nor in the store.
          // In all situations, we try to delete the schema from the store, but we don't take the
          // return value of the store operation into account. We only take the return value of the
          // catalog into account.
          try {
            store.delete(ident, SCHEMA, true);
          } catch (NoSuchEntityException e) {
            LOG.warn("The schema to be dropped does not exist in the store: {}", ident, e);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return droppedFromCatalog;
        });
  }

  private void importSchema(NameIdentifier identifier) {
    EntityCombinedSchema schema = internalLoadSchema(identifier);
    if (schema.imported()) {
      return;
    }

    StringIdentifier stringId = null;
    try {
      stringId = schema.stringIdentifier();
    } catch (IllegalArgumentException ie) {
      LOG.warn(FormattedErrorMessages.STRING_ID_PARSE_ERROR, ie.getMessage());
    }

    long uid;
    if (stringId != null) {
      // If the entity in the store doesn't match the one in the external system, we use the data
      // of external system to correct it.
      LOG.warn(
          "The Schema uid {} existed but still needs to be imported, this could be happened "
              + "when Schema is renamed by external systems not controlled by Gravitino. In this case, "
              + "we need to overwrite the stored entity to keep consistency.",
          stringId);
      uid = stringId.id();
    } else {
      // If the entity doesn't exist, we import the entity from the external system.
      uid = idGenerator.nextId();
    }

    SchemaEntity schemaEntity =
        SchemaEntity.builder()
            .withId(uid)
            .withName(identifier.name())
            .withNamespace(identifier.namespace())
            .withAuditInfo(
                AuditInfo.builder()
                    .withCreator(schema.auditInfo().creator())
                    .withCreateTime(schema.auditInfo().createTime())
                    .withLastModifier(schema.auditInfo().lastModifier())
                    .withLastModifiedTime(schema.auditInfo().lastModifiedTime())
                    .build())
            .build();
    try {
      store.put(schemaEntity, true);
    } catch (EntityAlreadyExistsException e) {
      LOG.error("Failed to import schema {} with id {} to the store.", identifier, uid, e);
      throw new UnsupportedOperationException(
          "Schema managed by multiple catalogs. This may cause unexpected issues such as privilege conflicts. "
              + "To resolve: Remove all catalogs managing this schema, then recreate one catalog to ensure single-catalog management.");
    } catch (Exception e) {
      LOG.error(FormattedErrorMessages.STORE_OP_FAILURE, "put", identifier, e);
      throw new RuntimeException("Fail to import schema entity to the store.", e);
    }
  }

  private EntityCombinedSchema internalLoadSchema(NameIdentifier ident) {
    NameIdentifier catalogIdentifier = getCatalogIdentifier(ident);
    Schema schema =
        doWithCatalog(
            catalogIdentifier,
            c -> c.doWithSchemaOps(s -> s.loadSchema(ident)),
            NoSuchSchemaException.class);

    // If the Schema is maintained by the entity store, we don't have to import.
    boolean isManagedSchema = isManagedEntity(catalogIdentifier, Capability.Scope.SCHEMA);
    if (isManagedSchema) {
      return EntityCombinedSchema.of(schema)
          .withHiddenProperties(
              getHiddenPropertyNames(
                  catalogIdentifier,
                  HasPropertyMetadata::schemaPropertiesMetadata,
                  schema.properties()))
          // The meta of managed schema is stored by Gravitino, we don't need to import it.
          .withImported(true /* imported */);
    }

    StringIdentifier stringId = getStringIdFromProperties(schema.properties());
    // Case 1: The schema is not created by Gravitino or the external system does not support
    // storing string identifiers.
    if (stringId == null) {
      SchemaEntity schemaEntity = getEntity(ident, SCHEMA, SchemaEntity.class);
      if (schemaEntity == null) {
        return EntityCombinedSchema.of(schema)
            .withHiddenProperties(
                getHiddenPropertyNames(
                    catalogIdentifier,
                    HasPropertyMetadata::schemaPropertiesMetadata,
                    schema.properties()))
            .withImported(false);
      }

      return EntityCombinedSchema.of(schema, schemaEntity)
          .withHiddenProperties(
              getHiddenPropertyNames(
                  catalogIdentifier,
                  HasPropertyMetadata::schemaPropertiesMetadata,
                  schema.properties()))
          // For some catalogs like PG, the identifier information is not stored in the schema's
          // metadata, we need to check if this schema is existed in the store, if so we don't
          // need to import.
          .withImported(true);
    }

    SchemaEntity schemaEntity =
        operateOnEntity(
            ident,
            identifier -> store.get(identifier, SCHEMA, SchemaEntity.class),
            "GET",
            stringId.id());

    return EntityCombinedSchema.of(schema, schemaEntity)
        .withHiddenProperties(
            getHiddenPropertyNames(
                catalogIdentifier,
                HasPropertyMetadata::schemaPropertiesMetadata,
                schema.properties()))
        .withImported(schemaEntity != null);
  }
}
