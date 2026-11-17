package com.superskidder.kylinops.backend.agentruntime.security;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import java.io.IOException;

public final class DeterministicProtobuf {
    private DeterministicProtobuf() {}

    public static byte[] serialize(MessageLite message) {
        byte[] output = new byte[message.getSerializedSize()];
        CodedOutputStream stream = CodedOutputStream.newInstance(output);
        stream.useDeterministicSerialization();
        try {
            message.writeTo(stream);
            stream.checkNoSpaceLeft();
            return output;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot serialize in-memory protobuf", exception);
        }
    }
}
