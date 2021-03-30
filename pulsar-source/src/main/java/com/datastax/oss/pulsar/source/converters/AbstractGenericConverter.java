package com.datastax.oss.pulsar.source.converters;

import com.datastax.cassandra.cdc.converter.Converter;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.protocol.internal.ProtocolConstants;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.schema.Field;
import org.apache.pulsar.client.api.schema.GenericRecord;
import org.apache.pulsar.client.api.schema.GenericRecordBuilder;
import org.apache.pulsar.client.api.schema.GenericSchema;
import org.apache.pulsar.client.api.schema.RecordSchemaBuilder;
import org.apache.pulsar.client.api.schema.SchemaBuilder;
import org.apache.pulsar.client.impl.schema.generic.GenericSchemaImpl;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractGenericConverter implements Converter<GenericRecord, Row, Object[]> {

    public final GenericSchema<GenericRecord> schema;
    public final List<ColumnMetadata> columns;
    public final SchemaInfo schemaInfo;

    public final Map<String, GenericSchema> udtSchemas = new ConcurrentHashMap<>();

    public AbstractGenericConverter(KeyspaceMetadata ksm, TableMetadata tm, List<ColumnMetadata> columns, SchemaType schemaType) {
        RecordSchemaBuilder recordSchemaBuilder = SchemaBuilder.record(ksm.getName() + "." + tm.getName().toString());
        for(ColumnMetadata cm : columns) {
            addFieldSchema(recordSchemaBuilder, ksm, cm.getName().toString(), cm.getType(), schemaType);
        }
        this.schemaInfo = recordSchemaBuilder.build(schemaType);
        this.schema = GenericSchemaImpl.of(schemaInfo);
        this.columns = columns;
    }

    RecordSchemaBuilder addFieldSchema(RecordSchemaBuilder recordSchemaBuilder,
                                 KeyspaceMetadata ksm,
                                 String fieldName,
                                 DataType dataType,
                                 SchemaType schemaType) {
        switch(dataType.getProtocolCode()) {
            case ProtocolConstants.DataType.UUID:
            case ProtocolConstants.DataType.TIMEUUID:
            case ProtocolConstants.DataType.ASCII:
            case ProtocolConstants.DataType.VARCHAR:
                recordSchemaBuilder.field(fieldName).type(SchemaType.STRING).optional();
                break;
            case ProtocolConstants.DataType.BLOB:
                recordSchemaBuilder.field(fieldName).type(SchemaType.BYTES).optional();
                break;
            case ProtocolConstants.DataType.TINYINT:
                recordSchemaBuilder.field(fieldName).type(SchemaType.INT8).optional();
                break;
            case ProtocolConstants.DataType.SMALLINT:
                recordSchemaBuilder.field(fieldName).type(SchemaType.INT16).optional();
                break;
            case ProtocolConstants.DataType.INT:
                recordSchemaBuilder.field(fieldName).type(SchemaType.INT32).optional();
                break;
            case ProtocolConstants.DataType.INET:
            case ProtocolConstants.DataType.DURATION:
            case ProtocolConstants.DataType.BIGINT:
                recordSchemaBuilder.field(fieldName).type(SchemaType.INT64).optional();
                break;
            case ProtocolConstants.DataType.BOOLEAN:
                recordSchemaBuilder.field(fieldName).type(SchemaType.BOOLEAN).optional();
                break;
            case ProtocolConstants.DataType.FLOAT:
                recordSchemaBuilder.field(fieldName).type(SchemaType.FLOAT).optional();
                break;
            case ProtocolConstants.DataType.DOUBLE:
                recordSchemaBuilder.field(fieldName).type(SchemaType.DOUBLE).optional();
                break;
            case ProtocolConstants.DataType.DATE:
                recordSchemaBuilder.field(fieldName).type(SchemaType.LOCAL_DATE_TIME).optional();
                break;
            case ProtocolConstants.DataType.TIMESTAMP:
                recordSchemaBuilder.field(fieldName).type(SchemaType.TIMESTAMP).optional();
                break;
            case ProtocolConstants.DataType.TIME:
                recordSchemaBuilder.field(fieldName).type(SchemaType.TIME).optional();
                break;
            case ProtocolConstants.DataType.UDT:
                recordSchemaBuilder
                        .field(fieldName, buildUDTSchema(ksm, dataType.asCql(false, true), schemaType))
                        .type(schemaType)
                        .optional();
                break;
            default:
                throw new UnsupportedOperationException("Unsupported DataType=" + dataType.getProtocolCode());
        }
        return recordSchemaBuilder;
    }

    GenericSchema buildUDTSchema(KeyspaceMetadata ksm, String typeName, SchemaType schemaType) {
        UserDefinedType userDefinedType = ksm.getUserDefinedType(CqlIdentifier.fromCql(typeName.substring(typeName.indexOf(".") + 1))).get();
        if (userDefinedType == null) {
            throw new IllegalStateException("UDT " + typeName + " not found");
        }
        RecordSchemaBuilder udtSchemaBuilder = SchemaBuilder.record(typeName);
        int i = 0;
        for(CqlIdentifier field : userDefinedType.getFieldNames()) {
            addFieldSchema(udtSchemaBuilder, ksm, field.toString(), userDefinedType.getFieldTypes().get(i++), schemaType);
        }
        SchemaInfo pcGenericSchemaInfo = udtSchemaBuilder.build(schemaType);
        GenericSchema genericSchema = GenericSchemaImpl.of(pcGenericSchemaInfo);
        udtSchemas.put(typeName, genericSchema);
        return genericSchema;
    }

    @Override
    public Schema<GenericRecord> getSchema() {
        return this.schema;
    }

    @Override
    public GenericRecord toConnectData(Row row) {
        GenericRecordBuilder genericRecordBuilder = schema.newRecordBuilder();
        for(ColumnMetadata cm : columns) {
            switch(cm.getType().getProtocolCode()) {
                case ProtocolConstants.DataType.UUID:
                case ProtocolConstants.DataType.TIMEUUID:
                    genericRecordBuilder.set(cm.getName().toString(), row.getUuid(cm.getName()).toString());
                    break;
                case ProtocolConstants.DataType.ASCII:
                case ProtocolConstants.DataType.VARCHAR:
                    genericRecordBuilder.set(cm.getName().toString(), row.getString(cm.getName()));
                    break;
                case ProtocolConstants.DataType.TINYINT:
                    genericRecordBuilder.set(cm.getName().toString(), row.getByte(cm.getName()));
                    break;
                case ProtocolConstants.DataType.SMALLINT:
                    genericRecordBuilder.set(cm.getName().toString(), row.getShort(cm.getName()));
                    break;
                case ProtocolConstants.DataType.INT:
                    genericRecordBuilder.set(cm.getName().toString(), row.getInt(cm.getName()));
                    break;
                case ProtocolConstants.DataType.INET:
                case ProtocolConstants.DataType.BIGINT:
                    genericRecordBuilder.set(cm.getName().toString(), row.getLong(cm.getName()));
                    break;
                case ProtocolConstants.DataType.DOUBLE:
                    genericRecordBuilder.set(cm.getName().toString(), row.getDouble(cm.getName()));
                    break;
                case ProtocolConstants.DataType.FLOAT:
                    genericRecordBuilder.set(cm.getName().toString(), row.getFloat(cm.getName()));
                    break;
                case ProtocolConstants.DataType.BOOLEAN:
                    genericRecordBuilder.set(cm.getName().toString(), row.getBoolean(cm.getName()));
                    break;
                case ProtocolConstants.DataType.DATE:
                    genericRecordBuilder.set(cm.getName().toString(), row.getLocalDate(cm.getName()));
                    break;
                case ProtocolConstants.DataType.DURATION:
                    genericRecordBuilder.set(cm.getName().toString(), row.getCqlDuration(cm.getName()));
                    break;
                case ProtocolConstants.DataType.TIME:
                    genericRecordBuilder.set(cm.getName().toString(), row.getLocalTime(cm.getName()));
                    break;
                case ProtocolConstants.DataType.UDT:
                    genericRecordBuilder.set(cm.getName().toString(), buildUDTValue(row.getUdtValue(cm.getName())));
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported DataType=" + cm.getType().getProtocolCode());
            }
        }
        return genericRecordBuilder.build();
    }

    GenericRecord buildUDTValue(UdtValue udtValue) {
        String typeName = udtValue.getType().getKeyspace() + "." + udtValue.getType().getName().asCql(true);
        GenericSchema genericSchema = udtSchemas.get(typeName);
        assert genericSchema != null : "Generic schema not found for UDT=" + typeName;
        GenericRecordBuilder genericRecordBuilder = genericSchema.newRecordBuilder();
        for(CqlIdentifier field : udtValue.getType().getFieldNames()) {
            DataType dataType = udtValue.getType(field);
            switch(dataType.getProtocolCode()) {
                case ProtocolConstants.DataType.UUID:
                case ProtocolConstants.DataType.TIMEUUID:
                    genericRecordBuilder.set(field.toString(), udtValue.getUuid(field).toString());
                    break;
                case ProtocolConstants.DataType.ASCII:
                case ProtocolConstants.DataType.VARCHAR:
                    genericRecordBuilder.set(field.toString(), udtValue.getString(field));
                    break;
                case ProtocolConstants.DataType.TINYINT:
                    genericRecordBuilder.set(field.toString(), udtValue.getByte(field));
                    break;
                case ProtocolConstants.DataType.SMALLINT:
                    genericRecordBuilder.set(field.toString(), udtValue.getShort(field));
                    break;
                case ProtocolConstants.DataType.INT:
                    genericRecordBuilder.set(field.toString(), udtValue.getInt(field));
                    break;
                case ProtocolConstants.DataType.INET:
                case ProtocolConstants.DataType.BIGINT:
                    genericRecordBuilder.set(field.toString(), udtValue.getLong(field));
                    break;
                case ProtocolConstants.DataType.DOUBLE:
                    genericRecordBuilder.set(field.toString(), udtValue.getDouble(field));
                    break;
                case ProtocolConstants.DataType.FLOAT:
                    genericRecordBuilder.set(field.toString(), udtValue.getFloat(field));
                    break;
                case ProtocolConstants.DataType.BOOLEAN:
                    genericRecordBuilder.set(field.toString(), udtValue.getBoolean(field));
                    break;
                case ProtocolConstants.DataType.DATE:
                    genericRecordBuilder.set(field.toString(), udtValue.getLocalDate(field));
                    break;
                case ProtocolConstants.DataType.DURATION:
                    genericRecordBuilder.set(field.toString(), udtValue.getCqlDuration(field));
                    break;
                case ProtocolConstants.DataType.TIME:
                    genericRecordBuilder.set(field.toString(), udtValue.getLocalTime(field));
                    break;
                case ProtocolConstants.DataType.UDT:
                    genericRecordBuilder.set(field.toString(), buildUDTValue(udtValue.getUdtValue(field)));
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported field="+field.toString() + " DataType=" + dataType.getProtocolCode());
            }
        }
        return genericRecordBuilder.build();
    }

    /**
     * Convert GenericRecord to primary key column values.
     *
     * @param genericRecord
     * @return
     */
    @Override
    public Object[] fromConnectData(GenericRecord genericRecord) {
        List<Object> pk = new ArrayList<>();
        for(Field field : genericRecord.getFields()) {
            pk.add(genericRecord.getField(field.getName()));
        }
        return pk.toArray(new Object[pk.size()]);
    }
}
