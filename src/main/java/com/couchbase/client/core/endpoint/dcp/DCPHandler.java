/**
 * Copyright (C) 2014 Couchbase, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALING
 * IN THE SOFTWARE.
 */

package com.couchbase.client.core.endpoint.dcp;

import com.couchbase.client.core.ResponseEvent;
import com.couchbase.client.core.endpoint.AbstractEndpoint;
import com.couchbase.client.core.endpoint.AbstractGenericHandler;
import com.couchbase.client.core.logging.CouchbaseLogger;
import com.couchbase.client.core.logging.CouchbaseLoggerFactory;
import com.couchbase.client.core.message.CouchbaseResponse;
import com.couchbase.client.core.message.ResponseStatus;
import com.couchbase.client.core.message.dcp.AbstractDCPRequest;
import com.couchbase.client.core.message.dcp.DCPRequest;
import com.couchbase.client.core.message.dcp.FailoverLogEntry;
import com.couchbase.client.core.message.dcp.MutationMessage;
import com.couchbase.client.core.message.dcp.OpenConnectionRequest;
import com.couchbase.client.core.message.dcp.OpenConnectionResponse;
import com.couchbase.client.core.message.dcp.RemoveMessage;
import com.couchbase.client.core.message.dcp.SnapshotMarkerMessage;
import com.couchbase.client.core.message.dcp.StreamRequestRequest;
import com.couchbase.client.core.message.dcp.StreamRequestResponse;
import com.couchbase.client.deps.io.netty.handler.codec.memcache.binary.BinaryMemcacheRequest;
import com.couchbase.client.deps.io.netty.handler.codec.memcache.binary.BinaryMemcacheResponseStatus;
import com.couchbase.client.deps.io.netty.handler.codec.memcache.binary.DefaultBinaryMemcacheRequest;
import com.couchbase.client.deps.io.netty.handler.codec.memcache.binary.FullBinaryMemcacheRequest;
import com.couchbase.client.deps.io.netty.handler.codec.memcache.binary.FullBinaryMemcacheResponse;
import com.lmax.disruptor.EventSink;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import rx.Scheduler;
import rx.functions.Action1;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * @author Sergey Avseyev
 * @since 1.1.0
 */
public class DCPHandler extends AbstractGenericHandler<FullBinaryMemcacheResponse, BinaryMemcacheRequest, DCPRequest> {
    public static final byte OP_OPEN_CONNECTION = (byte) 0x50;
    public static final byte OP_STREAM_REQUEST = (byte) 0x53;
    public static final byte OP_SNAPSHOT_MARKER = (byte) 0x56;
    public static final byte OP_MUTATION = (byte) 0x57;
    public static final byte OP_REMOVE = (byte) 0x58;
    private static final CouchbaseLogger LOGGER = CouchbaseLoggerFactory.getInstance(DCPHandler.class);
    /**
     * Maps stream identifiers to {@link DCPStream}. The identifiers put into
     * opaque fields of each packet of the stream and helps to multiplex streams.
     */
    private final Map<Integer, DCPStream> streams;

    /**
     * Counter for stream identifiers.
     */
    private int nextStreamId = 0;

    /**
     * Creates a new {@link DCPHandler} with the default queue for requests.
     *
     * @param endpoint       the {@link AbstractEndpoint} to coordinate with.
     * @param responseBuffer the {@link EventSink} to push responses into.
     */
    public DCPHandler(AbstractEndpoint endpoint, EventSink<ResponseEvent> responseBuffer) {
        this(endpoint, responseBuffer, new ArrayDeque<DCPRequest>());
    }

    /**
     * Creates a new {@link DCPHandler} with a custom queue for requests (suitable for tests).
     *
     * @param endpoint       the {@link AbstractEndpoint} to coordinate with.
     * @param responseBuffer the {@link EventSink} to push responses into.
     * @param queue          the queue which holds all outstanding open requests.
     */
    public DCPHandler(AbstractEndpoint endpoint, EventSink<ResponseEvent> responseBuffer, Queue<DCPRequest> queue) {
        super(endpoint, responseBuffer, queue);
        streams = new HashMap<Integer, DCPStream>();
    }

    /**
     * Convert the binary protocol status in a typesafe enum that can be acted upon later.
     *
     * @param status the status to convert.
     * @return the converted response status.
     */
    private static ResponseStatus convertStatus(final short status) {
        switch (status) {
            case BinaryMemcacheResponseStatus.SUCCESS:
                return ResponseStatus.SUCCESS;
            default:
                return ResponseStatus.FAILURE;
        }
    }

    @Override
    protected BinaryMemcacheRequest encodeRequest(ChannelHandlerContext ctx, DCPRequest msg) throws Exception {
        BinaryMemcacheRequest request;

        if (msg instanceof OpenConnectionRequest) {
            request = handleOpenConnectionRequest(ctx, (OpenConnectionRequest) msg);
        } else if (msg instanceof StreamRequestRequest) {
            request = handleStreamRequestRequest(ctx, (StreamRequestRequest) msg);
        } else {
            throw new IllegalArgumentException("Unknown incoming DCPRequest type "
                    + msg.getClass());
        }
        if (msg.partition() >= 0) {
            request.setReserved(msg.partition());
        }
        if (request instanceof FullBinaryMemcacheRequest) {
            ByteBuf content = ((FullBinaryMemcacheRequest) request).content();
            if (content != null) {
                content.retain();
            }
        }
        return request;
    }

    @Override
    protected CouchbaseResponse decodeResponse(ChannelHandlerContext ctx, FullBinaryMemcacheResponse msg)
            throws Exception {
        DCPRequest request = currentRequest();
        ResponseStatus status = convertStatus(msg.getStatus());

        CouchbaseResponse response = null;

        if (msg.getOpcode() == OP_OPEN_CONNECTION && request instanceof OpenConnectionRequest) {
            response = new OpenConnectionResponse(status, request);
        } else if (msg.getOpcode() == OP_STREAM_REQUEST && request instanceof StreamRequestRequest) {
            ByteBuf content = msg.content();
            Scheduler scheduler = env().scheduler();
            DCPStream stream = streams.get(msg.getOpaque());
            List<FailoverLogEntry> failoverLog = new ArrayList<FailoverLogEntry>(content.readableBytes() / 16);
            while (content.readableBytes() >= 16) {
                FailoverLogEntry entry = new FailoverLogEntry(content.readLong(), content.readLong());
                failoverLog.add(entry);
            }
            response = new StreamRequestResponse(status, stream.subject().onBackpressureBuffer().observeOn(scheduler),
                    failoverLog, request);
        } else {
            /**
             * FIXME
             * There are two cases:
             *
             * 1) Queue is empty:
             *    In this case outer method {@link AbstractGenericHandler#decode} picks 'null'
             *    as a current request, and it will might lead to NPEs because it does not cover
             *    scenario when we receive some packet without request in the queue.
             *
             * 2) Queue is not empty:
             *    In this case we might notify about errors wrong observable in the outer method,
             *    so we have to substitute it as soon as we detected that incoming message is not
             *    relevant to 'current request'
             */
            final DCPRequest oldRequest = currentRequest();
            final DCPStream stream = streams.get(msg.getOpaque());
            final DCPRequest dummy = new AbstractDCPRequest(stream.bucket(), null) {
            };
            dummy.observable().subscribe(new Action1<CouchbaseResponse>() {
                @Override
                public void call(CouchbaseResponse couchbaseResponse) {
                    // skip
                }
            }, new Action1<Throwable>() {
                @Override
                public void call(Throwable throwable) {
                    stream.subject().onError(throwable);
                }
            });
            try {
                currentRequest(dummy);
                handleDCPRequest(ctx, msg);
            } finally {
                currentRequest(oldRequest);
            }
        }

        if (response != null || request == null) {
            finishedDecoding();
        }
        return response;
    }

    /**
     * Handles incoming stream of DCP messages.
     */
    private void handleDCPRequest(ChannelHandlerContext ctx, FullBinaryMemcacheResponse msg) {
        final DCPStream stream = streams.get(msg.getOpaque());
        DCPRequest request = null;
        int flags = 0;

        switch (msg.getOpcode()) {
            case OP_SNAPSHOT_MARKER:
                long startSequenceNumber = 0;
                long endSequenceNumber = 0;
                if (msg.getExtrasLength() > 0) {
                    final ByteBuf extrasReleased = msg.getExtras();
                    final ByteBuf extras = ctx.alloc().buffer(msg.getExtrasLength());
                    extras.writeBytes(extrasReleased, extrasReleased.readerIndex(), extrasReleased.readableBytes());
                    startSequenceNumber = extras.readLong();
                    endSequenceNumber = extras.readLong();
                    flags = extras.readInt();
                    extras.release();
                }
                request = new SnapshotMarkerMessage(msg.getStatus(), startSequenceNumber, endSequenceNumber,
                        flags, stream.bucket());
                break;

            case OP_MUTATION:
                int expiration = 0;
                int lockTime = 0;

                if (msg.getExtrasLength() > 0) {
                    final ByteBuf extrasReleased = msg.getExtras();
                    final ByteBuf extras = ctx.alloc().buffer(msg.getExtrasLength());
                    extras.writeBytes(extrasReleased, extrasReleased.readerIndex(), extrasReleased.readableBytes());
                    extras.skipBytes(16); /* by_seqno, rev_seqno */
                    flags = extras.readInt();
                    expiration = extras.readInt();
                    lockTime = extras.readInt();
                    extras.release();
                }
                request = new MutationMessage(msg.getStatus(), msg.getKey(), msg.content().retain(),
                        expiration, flags, lockTime, msg.getCAS(), stream.bucket());
                // TODO: release message content if nobody consumed it
                break;
            case OP_REMOVE:
                request = new RemoveMessage(msg.getStatus(), msg.getKey(), msg.getCAS(), stream.bucket());
                break;
            default:
                LOGGER.info("Unhandled DCP message: {}, {}", msg.getOpcode(), msg);
        }
        if (request != null) {
            stream.subject().onNext(request);
        }
    }

    private BinaryMemcacheRequest handleOpenConnectionRequest(ChannelHandlerContext ctx, OpenConnectionRequest msg) {
        ByteBuf extras = ctx.alloc().buffer(8);
        extras.writeInt(msg.sequenceNumber());
        extras.writeInt(msg.type().flags());
        byte extrasLength = (byte) extras.readableBytes();

        String key = msg.connectionName();
        short keyLength = (short) key.length();
        BinaryMemcacheRequest request = new DefaultBinaryMemcacheRequest(key, extras);
        request.setOpcode(OP_OPEN_CONNECTION);
        request.setKeyLength(keyLength);
        request.setExtrasLength(extrasLength);
        request.setTotalBodyLength(keyLength + extrasLength);

        return request;
    }

    private BinaryMemcacheRequest handleStreamRequestRequest(ChannelHandlerContext ctx, StreamRequestRequest msg) {
        ByteBuf extras = ctx.alloc().buffer(48);
        extras.writeInt(0).writeInt(0);
        extras.writeLong(msg.startSequenceNumber());
        extras.writeLong(msg.endSequenceNumber());
        extras.writeLong(msg.vbucketUUID());
        extras.writeLong(msg.snapshotStartSequenceNumber());
        extras.writeLong(msg.snapshotEndSequenceNumber());
        byte extrasLength = (byte) extras.readableBytes();

        BinaryMemcacheRequest request = new DefaultBinaryMemcacheRequest(extras);
        request.setOpcode(OP_STREAM_REQUEST);
        request.setExtrasLength(extrasLength);
        request.setTotalBodyLength(extrasLength);
        request.setOpaque(initializeStream(msg));

        return request;
    }

    private int initializeStream(StreamRequestRequest msg) {
        int streamId = nextStreamId++;
        DCPStream stream = new DCPStream(streamId, msg.bucket());
        streams.put(streamId, stream);
        return streamId;
    }

}
