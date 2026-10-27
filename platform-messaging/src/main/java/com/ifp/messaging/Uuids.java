package com.ifp.messaging;

import java.nio.ByteBuffer;
import java.util.UUID;

/** UUID to BINARY(16) in the same byte order Hibernate uses, so IDs written here match the services' entities. */
public final class Uuids {

    private Uuids() {
    }

    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] raw) {
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
