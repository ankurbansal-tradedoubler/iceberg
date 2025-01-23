/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.tabular.iceberg.connect.channel.events;

import static org.apache.iceberg.avro.AvroSchemaUtil.FIELD_ID_PROP;

import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types.StructType;

public class CommitResponsePayload implements Payload {

  private UUID commitId;
  private TableName tableName;
  private List<DataFile> dataFiles;
  private List<DeleteFile> deleteFiles;
  private List<TopicPartitionOffset> assignments;
  private Schema avroSchema;

  public CommitResponsePayload(Schema avroSchema) {
    this.avroSchema = avroSchema;
  }

  public CommitResponsePayload(
      StructType partitionType,
      UUID commitId,
      TableName tableName,
      List<DataFile> dataFiles,
      List<DeleteFile> deleteFiles,
      List<TopicPartitionOffset> assignments) {
    this.commitId = commitId;
    this.tableName = tableName;
    this.dataFiles = dataFiles;
    this.deleteFiles = deleteFiles;
    this.assignments = assignments;

    StructType dataFileStruct = DataFile.getType(partitionType);
    Schema dataFileSchema =
        AvroSchemaUtil.convert(
            dataFileStruct,
            ImmutableMap.of(
                dataFileStruct, "org.apache.iceberg.GenericDataFile",
                partitionType, "org.apache.iceberg.PartitionData"));
    Schema deleteFileSchema =
        AvroSchemaUtil.convert(
            dataFileStruct,
            ImmutableMap.of(
                dataFileStruct, "org.apache.iceberg.GenericDeleteFile",
                partitionType, "org.apache.iceberg.PartitionData"));

    this.avroSchema =
        SchemaBuilder.builder()
            .record(getClass().getName())
            .fields()
            .name("commitId")
            .prop(FIELD_ID_PROP, DUMMY_FIELD_ID)
            .type(UUID_SCHEMA)
            .noDefault()
            .name("tableName")
            .prop(FIELD_ID_PROP, DUMMY_FIELD_ID)
            .type(TableName.AVRO_SCHEMA)
            .noDefault()
            .name("dataFiles")
            .prop(FIELD_ID_PROP, DUMMY_FIELD_ID)
            .type()
            .nullable()
            .array()
            .items(dataFileSchema)
            .noDefault()
            .name("deleteFiles")
            .prop(FIELD_ID_PROP, DUMMY_FIELD_ID)
            .type()
            .nullable()
            .array()
            .items(deleteFileSchema)
            .noDefault()
            .name("assignments")
            .prop(FIELD_ID_PROP, DUMMY_FIELD_ID)
            .type()
            .nullable()
            .array()
            .items(TopicPartitionOffset.AVRO_SCHEMA)
            .noDefault()
            .endRecord();
  }

  public UUID getCommitId() {
    return commitId;
  }

  public TableName getTableName() {
    return tableName;
  }

  public List<DataFile> getDataFiles() {
    return dataFiles;
  }

  public List<DeleteFile> getDeleteFiles() {
    return deleteFiles;
  }

  public List<TopicPartitionOffset> getAssignments() {
    return assignments;
  }

  @Override
  public Schema getSchema() {
    return avroSchema;
  }

  @Override
  @SuppressWarnings("unchecked")
  public void put(int i, Object v) {
    switch (i) {
      case 0:
        this.commitId = (UUID) v;
        return;
      case 1:
        this.tableName = (TableName) v;
        return;
      case 2:
        this.dataFiles = (List<DataFile>) v;
        return;
      case 3:
        this.deleteFiles = (List<DeleteFile>) v;
        return;
      case 4:
        this.assignments = (List<TopicPartitionOffset>) v;
        return;
      default:
        // ignore the object, it must be from a newer version of the format
    }
  }

  @Override
  public Object get(int i) {
    switch (i) {
      case 0:
        return commitId;
      case 1:
        return tableName;
      case 2:
        return dataFiles;
      case 3:
        return deleteFiles;
      case 4:
        return assignments;
      default:
        throw new UnsupportedOperationException("Unknown field ordinal: " + i);
    }
  }
}
