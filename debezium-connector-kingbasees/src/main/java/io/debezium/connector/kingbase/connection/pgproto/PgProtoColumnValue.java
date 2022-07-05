/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.kingbase.connection.pgproto;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kingbase8.geometric.KBpoint;
import com.kingbase8.jdbc.KbArray;
import com.kingbase8.util.KBmoney;

import io.debezium.connector.kingbase.KbOid;
import io.debezium.connector.kingbase.KingbaseESStreamingChangeEventSource.PgConnectionSupplier;
import io.debezium.connector.kingbase.KingbaseESType;
import io.debezium.connector.kingbase.KingbaseESValueConverter;
import io.debezium.connector.kingbase.TypeRegistry;
import io.debezium.connector.kingbase.connection.AbstractColumnValue;
import io.debezium.connector.kingbase.connection.wal2json.DateTimeFormat;
import io.debezium.connector.kingbase.proto.PgProto;
import io.debezium.data.SpecialValueDecimal;
import io.debezium.time.Conversions;

/**
 * Replication message column sent by <a href="https://github.com/debezium/postgres-decoderbufs">Postgres Decoderbufs</>
 *
 * @author Chris Cranford
 */
public class PgProtoColumnValue extends AbstractColumnValue<PgProto.DatumMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PgProtoColumnValue.class);

    /**
     * A number used by PostgreSQL to define minimum timestamp (inclusive).
     * Defined in timestamp.h
     */
    private static final long TIMESTAMP_MIN = -211813488000000000L;

    /**
     * A number used by PostgreSQL to define maximum timestamp (exclusive).
     * Defined in timestamp.h
     */
    private static final long TIMESTAMP_MAX = 9223371331200000000L;

    private PgProto.DatumMessage value;

    public PgProtoColumnValue(PgProto.DatumMessage value) {
        this.value = value;
    }

    @Override
    public PgProto.DatumMessage getRawValue() {
        return value;
    }

    @Override
    public boolean isNull() {
        return value.hasDatumMissing();
    }

    @Override
    public String asString() {
        if (value.hasDatumString()) {
            return value.getDatumString();
        }
        else if (value.hasDatumBytes()) {
            return new String(asByteArray(), Charset.forName("UTF-8"));
        }
        return null;
    }

    @Override
    public Boolean asBoolean() {
        if (value.hasDatumBool()) {
            return value.getDatumBool();
        }

        final String s = asString();
        if (s != null) {
            if (s.equalsIgnoreCase("t")) {
                return Boolean.TRUE;
            }
            else if (s.equalsIgnoreCase("f")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    @Override
    public Integer asInteger() {
        if (value.hasDatumInt32()) {
            return value.getDatumInt32();
        }

        final String s = asString();
        return s != null ? Integer.valueOf(s) : null;
    }

    @Override
    public Long asLong() {
        if (value.hasDatumInt64()) {
            return value.getDatumInt64();
        }

        final String s = asString();
        return s != null ? Long.valueOf(s) : null;
    }

    @Override
    public Float asFloat() {
        if (value.hasDatumFloat()) {
            return value.getDatumFloat();
        }

        final String s = asString();
        return s != null ? Float.valueOf(s) : null;
    }

    @Override
    public Double asDouble() {
        if (value.hasDatumDouble()) {
            return value.getDatumDouble();
        }

        final String s = asString();
        return s != null ? Double.valueOf(s) : null;
    }

    @Override
    public Object asDecimal() {
        if (value.hasDatumDouble()) {
            return value.getDatumDouble();
        }

        final String s = asString();
        if (s != null) {
            return KingbaseESValueConverter.toSpecialValue(s).orElseGet(() -> new SpecialValueDecimal(new BigDecimal(s)));
        }
        return null;
    }

    @Override
    public byte[] asByteArray() {
        return value.hasDatumBytes() ? value.getDatumBytes().toByteArray() : null;
    }

    @Override
    public LocalDate asLocalDate() {
        if (value.hasDatumInt32()) {
            return LocalDate.ofEpochDay(value.getDatumInt32());
        }

        final String s = asString();
        return s != null ? DateTimeFormat.get().date(s) : null;
    }

    @Override
    public Object asTime() {
        if (value.hasDatumInt64()) {
            return Duration.of(value.getDatumInt64(), ChronoUnit.MICROS);
        }

        final String s = asString();
        if (s != null) {
            return DateTimeFormat.get().time(s);
        }
        return null;
    }

    @Override
    public OffsetTime asOffsetTimeUtc() {
        if (value.hasDatumDouble()) {
            return Conversions.toInstantFromMicros((long) value.getDatumDouble()).atOffset(ZoneOffset.UTC).toOffsetTime();
        }

        final String s = asString();
        return s != null ? DateTimeFormat.get().timeWithTimeZone(s) : null;
    }

    @Override
    public OffsetDateTime asOffsetDateTimeAtUtc() {
        if (value.hasDatumInt64()) {
            if (value.getDatumInt64() >= TIMESTAMP_MAX) {
                LOGGER.trace("Infinite(+) value '{}' arrived from database", value.getDatumInt64());
                return KingbaseESValueConverter.POSITIVE_INFINITY_OFFSET_DATE_TIME;
            }
            else if (value.getDatumInt64() < TIMESTAMP_MIN) {
                LOGGER.trace("Infinite(-) value '{}' arrived from database", value.getDatumInt64());
                return KingbaseESValueConverter.NEGATIVE_INFINITY_OFFSET_DATE_TIME;
            }
            return Conversions.toInstantFromMicros(value.getDatumInt64()).atOffset(ZoneOffset.UTC);
        }

        final String s = asString();
        return s != null ? DateTimeFormat.get().timestampWithTimeZoneToOffsetDateTime(s).withOffsetSameInstant(ZoneOffset.UTC) : null;
    }

    @Override
    public Instant asInstant() {
        if (value.hasDatumInt64()) {
            if (value.getDatumInt64() >= TIMESTAMP_MAX) {
                LOGGER.trace("Infinite(+) value '{}' arrived from database", value.getDatumInt64());
                return KingbaseESValueConverter.POSITIVE_INFINITY_INSTANT;
            }
            else if (value.getDatumInt64() < TIMESTAMP_MIN) {
                LOGGER.trace("Infinite(-) value '{}' arrived from database", value.getDatumInt64());
                return KingbaseESValueConverter.NEGATIVE_INFINITY_INSTANT;
            }
            return Conversions.toInstantFromMicros(value.getDatumInt64());
        }

        final String s = asString();
        return s != null ? DateTimeFormat.get().timestampToInstant(asString()) : null;
    }

    @Override
    public Object asLocalTime() {
        return asTime();
    }

    @Override
    public Object asInterval() {
        if (value.hasDatumDouble()) {
            return value.getDatumDouble();
        }

        final String s = asString();
        return s != null ? super.asInterval() : null;
    }

    @Override
    public KBmoney asMoney() {
        if (value.hasDatumInt64()) {
            return new KBmoney(value.getDatumInt64() / 100.0);
        }
        return super.asMoney();
    }

    @Override
    public KBpoint asPoint() {
        if (value.hasDatumPoint()) {
            PgProto.Point datumPoint = value.getDatumPoint();
            return new KBpoint(datumPoint.getX(), datumPoint.getY());
        }
        else if (value.hasDatumBytes()) {
            return super.asPoint();
        }
        return null;
    }

    @Override
    public boolean isArray(KingbaseESType type) {
        final int oidValue = type.getOid();
        switch (oidValue) {
            case KbOid.INT2_ARRAY:
            case KbOid.INT4_ARRAY:
            case KbOid.INT8_ARRAY:
            case KbOid.TEXT_ARRAY:
            case KbOid.NUMERIC_ARRAY:
            case KbOid.FLOAT4_ARRAY:
            case KbOid.FLOAT8_ARRAY:
            case KbOid.BOOL_ARRAY:
            case KbOid.DATE_ARRAY:
            case KbOid.TIME_ARRAY:
            case KbOid.TIMETZ_ARRAY:
            case KbOid.TIMESTAMP_ARRAY:
            case KbOid.TIMESTAMPTZ_ARRAY:
            case KbOid.BYTEA_ARRAY:
            case KbOid.VARCHAR_ARRAY:
            case KbOid.OID_ARRAY:
            case KbOid.BPCHAR_ARRAY:
            case KbOid.MONEY_ARRAY:
            case KbOid.NAME_ARRAY:
            case KbOid.INTERVAL_ARRAY:
            case KbOid.CHAR_ARRAY:
            case KbOid.VARBIT_ARRAY:
            case KbOid.UUID_ARRAY:
            case KbOid.XML_ARRAY:
            case KbOid.POINT_ARRAY:
            case KbOid.JSONB_ARRAY:
            case KbOid.JSON_ARRAY:
            case KbOid.REF_CURSOR_ARRAY:
            case KbOid.INET_ARRAY:
            case KbOid.CIDR_ARRAY:
            case KbOid.MACADDR_ARRAY:
            case KbOid.MACADDR8_ARRAY:
            case KbOid.TSRANGE_ARRAY:
            case KbOid.TSTZRANGE_ARRAY:
            case KbOid.DATERANGE_ARRAY:
            case KbOid.INT4RANGE_ARRAY:
            case KbOid.NUM_RANGE_ARRAY:
            case KbOid.INT8RANGE_ARRAY:
                return true;
            default:
                return type.isArrayType();
        }
    }

    @Override
    public Object asArray(String columnName, KingbaseESType type, String fullType, PgConnectionSupplier connection) {
        // Currently the logical decoding plugin sends unhandled types as a byte array containing the string
        // representation (in Postgres) of the array value.
        // The approach to decode this is sub-optimal but the only way to improve this is to update the plugin.
        // Reasons for it being sub-optimal include:
        // 1. It requires a Postgres JDBC connection to deserialize
        // 2. The byte-array is a serialised string but we make the assumption its UTF-8 encoded (which it will
        // be in most cases)
        // 3. For larger arrays and especially 64-bit integers and the like it is less efficient sending string
        // representations over the wire.
        try {
            byte[] data = asByteArray();
            if (data == null) {
                return null;
            }
            String dataString = new String(data, Charset.forName("UTF-8"));
            KbArray arrayData = new KbArray(connection.get(), (int) value.getColumnType(), dataString);
            Object deserializedArray = arrayData.getArray();
            return Arrays.asList((Object[]) deserializedArray);
        }
        catch (SQLException e) {
            LOGGER.warn("Unexpected exception trying to process PgArray column '{}'", value.getColumnName(), e);
        }
        return null;
    }

    @Override
    public Object asDefault(TypeRegistry typeRegistry, int columnType, String columnName, String fullType, boolean includeUnknownDatatypes,
                            PgConnectionSupplier connection) {
        final KingbaseESType type = typeRegistry.get(columnType);
        if (type.getOid() == typeRegistry.geometryOid() ||
                type.getOid() == typeRegistry.geographyOid() ||
                type.getOid() == typeRegistry.citextOid() ||
                type.getOid() == typeRegistry.hstoreOid()) {
            return asByteArray();
        }

        // unknown data type is sent by decoder as binary value
        if (includeUnknownDatatypes) {
            return asByteArray();
        }

        return null;
    }
}
