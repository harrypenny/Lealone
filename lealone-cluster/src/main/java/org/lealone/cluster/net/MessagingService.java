/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.cluster.net;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOError;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.cliffc.high_scale_lib.NonBlockingHashMap;
import org.lealone.cluster.concurrent.LealoneExecutorService;
import org.lealone.cluster.concurrent.ScheduledExecutors;
import org.lealone.cluster.concurrent.Stage;
import org.lealone.cluster.concurrent.StageManager;
import org.lealone.cluster.config.DatabaseDescriptor;
import org.lealone.cluster.config.EncryptionOptions.ServerEncryptionOptions;
import org.lealone.cluster.exceptions.ConfigurationException;
import org.lealone.cluster.gms.EchoMessage;
import org.lealone.cluster.gms.GossipDigestAck;
import org.lealone.cluster.gms.GossipDigestAck2;
import org.lealone.cluster.gms.GossipDigestSyn;
import org.lealone.cluster.io.DataOutputPlus;
import org.lealone.cluster.io.IVersionedSerializer;
import org.lealone.cluster.locator.ILatencySubscriber;
import org.lealone.cluster.metrics.ConnectionMetrics;
import org.lealone.cluster.metrics.DroppedMessageMetrics;
import org.lealone.cluster.security.SSLFactory;
import org.lealone.cluster.utils.ExpiringMap;
import org.lealone.cluster.utils.FBUtilities;
import org.lealone.cluster.utils.FileUtils;
import org.lealone.cluster.utils.Pair;
import org.lealone.cluster.utils.concurrent.SimpleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

@SuppressWarnings({ "rawtypes", "deprecation" })
public final class MessagingService implements MessagingServiceMBean {
    public static final String MBEAN_NAME = "org.lealone.cluster:type=MessagingService";

    // 8 bits version, so don't waste versions
    public static final int VERSION_12 = 6;
    public static final int VERSION_20 = 7;
    public static final int VERSION_21 = 8;
    public static final int VERSION_30 = 9;
    public static final int current_version = VERSION_30;

    public static final String FAILURE_CALLBACK_PARAM = "CAL_BAC";
    public static final byte[] ONE_BYTE = new byte[1];
    public static final String FAILURE_RESPONSE_PARAM = "FAIL";

    /**
     * we preface every message with this number so the recipient can validate the sender is sane
     */
    public static final int PROTOCOL_MAGIC = 0xCA552DFA;

    private boolean allNodesAtLeast21 = true;

    /* All verb handler identifiers */
    public enum Verb {
        REQUEST_RESPONSE, // client-initiated reads and writes
        GOSSIP_DIGEST_SYN,
        GOSSIP_DIGEST_ACK,
        GOSSIP_DIGEST_ACK2,
        GOSSIP_SHUTDOWN,
        INTERNAL_RESPONSE, // responses to internal calls
        ECHO,
        // remember to add new verbs at the end, since we serialize by ordinal
        UNUSED_1,
        UNUSED_2,
        UNUSED_3;
    }

    public static final EnumMap<MessagingService.Verb, Stage> verbStages = new EnumMap<MessagingService.Verb, Stage>(
            MessagingService.Verb.class) {
        {
            put(Verb.REQUEST_RESPONSE, Stage.REQUEST_RESPONSE);
            put(Verb.INTERNAL_RESPONSE, Stage.INTERNAL_RESPONSE);

            put(Verb.GOSSIP_DIGEST_ACK, Stage.GOSSIP);
            put(Verb.GOSSIP_DIGEST_ACK2, Stage.GOSSIP);
            put(Verb.GOSSIP_DIGEST_SYN, Stage.GOSSIP);
            put(Verb.GOSSIP_SHUTDOWN, Stage.GOSSIP);
            put(Verb.ECHO, Stage.GOSSIP);

            put(Verb.UNUSED_1, Stage.INTERNAL_RESPONSE);
            put(Verb.UNUSED_2, Stage.INTERNAL_RESPONSE);
            put(Verb.UNUSED_3, Stage.INTERNAL_RESPONSE);
        }
    };

    /**
     * Messages we receive in IncomingTcpConnection have a Verb that tells us what kind of message it is.
     * Most of the time, this is enough to determine how to deserialize the message payload.
     * The exception is the REQUEST_RESPONSE verb, which just means "a reply to something you told me to do."
     * Traditionally, this was fine since each VerbHandler knew what type of payload it expected, and
     * handled the deserialization itself.  Now that we do that in ITC, to avoid the extra copy to an
     * intermediary byte[] (See lealone-3716), we need to wire that up to the CallbackInfo object
     * (see below).
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> verbSerializers = new EnumMap<Verb, IVersionedSerializer<?>>(
            Verb.class) {
        {
            put(Verb.REQUEST_RESPONSE, CallbackDeterminedSerializer.instance);
            put(Verb.INTERNAL_RESPONSE, CallbackDeterminedSerializer.instance);
            put(Verb.GOSSIP_DIGEST_ACK, GossipDigestAck.serializer);
            put(Verb.GOSSIP_DIGEST_ACK2, GossipDigestAck2.serializer);
            put(Verb.GOSSIP_DIGEST_SYN, GossipDigestSyn.serializer);
            put(Verb.ECHO, EchoMessage.serializer);
        }
    };

    /**
     * A Map of what kind of serializer to wire up to a REQUEST_RESPONSE callback, based on outbound Verb.
     */
    public static final EnumMap<Verb, IVersionedSerializer<?>> callbackDeserializers = new EnumMap<>(Verb.class);

    /* This records all the results mapped by message Id */
    private final ExpiringMap<Integer, CallbackInfo> callbacks;

    /**
     * a placeholder class that means "deserialize using the callback." We can't implement this without
     * special-case code in InboundTcpConnection because there is no way to pass the message id to IVersionedSerializer.
     */
    static class CallbackDeterminedSerializer implements IVersionedSerializer<Object> {
        public static final CallbackDeterminedSerializer instance = new CallbackDeterminedSerializer();

        @Override
        public Object deserialize(DataInput in, int version) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void serialize(Object o, DataOutputPlus out, int version) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public long serializedSize(Object o, int version) {
            throw new UnsupportedOperationException();
        }
    }

    /* Lookup table for registering message handlers based on the verb. */
    private final Map<Verb, IVerbHandler> verbHandlers;

    private final ConcurrentMap<InetAddress, OutboundTcpConnectionPool> connectionManagers = new NonBlockingHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);
    private static final int LOG_DROPPED_INTERVAL_IN_MS = 5000;

    private final List<SocketThread> socketThreads = Lists.newArrayList();
    private final SimpleCondition listenGate;

    /**
     * Verbs it's okay to drop if the request has been queued longer than the request timeout.  These
     * all correspond to client requests or something triggered by them; we don't want to
     * drop internal messages like bootstrap or repair notifications.
     */
    public static final EnumSet<Verb> DROPPABLE_VERBS = EnumSet.of(Verb.REQUEST_RESPONSE);

    // total dropped message counts for server lifetime
    private final Map<Verb, DroppedMessageMetrics> droppedMessages = new EnumMap<Verb, DroppedMessageMetrics>(
            Verb.class);
    // dropped count when last requested for the Recent api.  high concurrency isn't necessary here.
    private final Map<Verb, Integer> lastDroppedInternal = new EnumMap<Verb, Integer>(Verb.class);

    private final List<ILatencySubscriber> subscribers = new ArrayList<ILatencySubscriber>();

    // protocol versions of the other nodes in the cluster
    private final ConcurrentMap<InetAddress, Integer> versions = new NonBlockingHashMap<InetAddress, Integer>();

    private static class MSHandle {
        public static final MessagingService instance = new MessagingService();
    }

    public static MessagingService instance() {
        return MSHandle.instance;
    }

    private MessagingService() {
        for (Verb verb : DROPPABLE_VERBS) {
            droppedMessages.put(verb, new DroppedMessageMetrics(verb));
            lastDroppedInternal.put(verb, 0);
        }

        listenGate = new SimpleCondition();
        verbHandlers = new EnumMap<Verb, IVerbHandler>(Verb.class);
        Runnable logDropped = new Runnable() {
            @Override
            public void run() {
                logDroppedMessages();
            }
        };
        ScheduledExecutors.scheduledTasks.scheduleWithFixedDelay(logDropped, LOG_DROPPED_INTERVAL_IN_MS,
                LOG_DROPPED_INTERVAL_IN_MS, TimeUnit.MILLISECONDS);

        Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, ?> timeoutReporter = //
        new Function<Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>>, Object>() {
            @Override
            public Object apply(Pair<Integer, ExpiringMap.CacheableObject<CallbackInfo>> pair) {
                final CallbackInfo expiredCallbackInfo = pair.right.value;
                maybeAddLatency(expiredCallbackInfo.callback, expiredCallbackInfo.target, pair.right.timeout);
                ConnectionMetrics.totalTimeouts.mark();
                getConnectionPool(expiredCallbackInfo.target).incrementTimeout();
                if (expiredCallbackInfo.isFailureCallback()) {
                    StageManager.getStage(Stage.INTERNAL_RESPONSE).submit(new Runnable() {
                        @Override
                        public void run() {
                            ((IAsyncCallbackWithFailure) expiredCallbackInfo.callback)
                                    .onFailure(expiredCallbackInfo.target);
                        }
                    });
                }
                return null;
            }
        };

        callbacks = new ExpiringMap<Integer, CallbackInfo>(DatabaseDescriptor.getRpcTimeout(), timeoutReporter);

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(this, new ObjectName(MBEAN_NAME));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Track latency information for the dynamic snitch
     *
     * @param cb      the callback associated with this message -- this lets us know if it's a message type we're interested in
     * @param address the host that replied to the message
     * @param latency
     */
    public void maybeAddLatency(IAsyncCallback cb, InetAddress address, long latency) {
        if (cb.isLatencyForSnitch())
            addLatency(address, latency);
    }

    public void addLatency(InetAddress address, long latency) {
        for (ILatencySubscriber subscriber : subscribers)
            subscriber.receiveTiming(address, latency);
    }

    /**
     * called from gossiper when it notices a node is not responding.
     */
    public void convict(InetAddress ep) {
        logger.debug("Resetting pool for {}", ep);
        getConnectionPool(ep).reset();
    }

    /**
     * Listen on the specified port.
     *
     * @param localEp InetAddress whose port to listen on.
     */
    public void listen(InetAddress localEp) throws ConfigurationException {
        callbacks.reset(); // hack to allow tests to stop/restart MS
        for (ServerSocket ss : getServerSockets(localEp)) {
            SocketThread th = new SocketThread(ss, "ACCEPT-" + localEp);
            th.start();
            socketThreads.add(th);
        }
        listenGate.signalAll();
    }

    private List<ServerSocket> getServerSockets(InetAddress localEp) throws ConfigurationException {
        final List<ServerSocket> ss = new ArrayList<ServerSocket>(2);
        if (DatabaseDescriptor.getServerEncryptionOptions().internode_encryption != ServerEncryptionOptions.InternodeEncryption.none) {
            try {
                ss.add(SSLFactory.getServerSocket(DatabaseDescriptor.getServerEncryptionOptions(), localEp,
                        DatabaseDescriptor.getSSLStoragePort()));
            } catch (IOException e) {
                throw new ConfigurationException("Unable to create ssl socket", e);
            }
            // setReuseAddress happens in the factory.
            logger.info("Starting Encrypted Messaging Service on SSL port {}", DatabaseDescriptor.getSSLStoragePort());
        }

        if (DatabaseDescriptor.getServerEncryptionOptions().internode_encryption != ServerEncryptionOptions.InternodeEncryption.all) {
            ServerSocketChannel serverChannel = null;
            try {
                serverChannel = ServerSocketChannel.open();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            ServerSocket socket = serverChannel.socket();
            try {
                socket.setReuseAddress(true);
            } catch (SocketException e) {
                throw new ConfigurationException("Insufficient permissions to setReuseAddress", e);
            }
            InetSocketAddress address = new InetSocketAddress(localEp, DatabaseDescriptor.getStoragePort());
            try {
                socket.bind(address, 500);
            } catch (BindException e) {
                if (e.getMessage().contains("in use"))
                    throw new ConfigurationException(
                            address
                                    + " is in use by another process.  Change listen_address:storage_port in lealone.yaml to values that do not conflict with other services");
                else if (e.getMessage().contains("Cannot assign requested address"))
                    throw new ConfigurationException(
                            "Unable to bind to address "
                                    + address
                                    + ". Set listen_address in lealone.yaml to an interface you can bind to, e.g., your private IP address on EC2");
                else
                    throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            logger.info("Starting Messaging Service on port {}", DatabaseDescriptor.getStoragePort());
            ss.add(socket);
        }
        return ss;
    }

    public void waitUntilListening() {
        try {
            listenGate.await();
        } catch (InterruptedException ie) {
            logger.debug("await interrupted");
        }
    }

    public boolean isListening() {
        return listenGate.isSignaled();
    }

    public void destroyConnectionPool(InetAddress to) {
        OutboundTcpConnectionPool cp = connectionManagers.get(to);
        if (cp == null)
            return;
        cp.close();
        connectionManagers.remove(to);
    }

    public OutboundTcpConnectionPool getConnectionPool(InetAddress to) {
        OutboundTcpConnectionPool cp = connectionManagers.get(to);
        if (cp == null) {
            cp = new OutboundTcpConnectionPool(to);
            OutboundTcpConnectionPool existingPool = connectionManagers.putIfAbsent(to, cp);
            if (existingPool != null)
                cp = existingPool;
            else
                cp.start();
        }
        cp.waitForStarted();
        return cp;
    }

    public OutboundTcpConnection getConnection(InetAddress to, MessageOut msg) {
        return getConnectionPool(to).getConnection(msg);
    }

    /**
     * Register a verb and the corresponding verb handler with the
     * Messaging Service.
     *
     * @param verb
     * @param verbHandler handler for the specified verb
     */
    public void registerVerbHandlers(Verb verb, IVerbHandler verbHandler) {
        assert !verbHandlers.containsKey(verb);
        verbHandlers.put(verb, verbHandler);
    }

    /**
     * This method returns the verb handler associated with the registered
     * verb. If no handler has been registered then null is returned.
     *
     * @param type for which the verb handler is sought
     * @return a reference to IVerbHandler which is the handler for the specified verb
     */
    public IVerbHandler getVerbHandler(Verb type) {
        return verbHandlers.get(type);
    }

    public int addCallback(IAsyncCallback cb, MessageOut message, InetAddress to, long timeout, boolean failureCallback) {
        int messageId = nextId();
        CallbackInfo previous = callbacks.put(messageId,
                new CallbackInfo(to, cb, callbackDeserializers.get(message.verb), failureCallback), timeout);
        assert previous == null : String.format("Callback already exists for id %d! (%s)", messageId, previous);
        return messageId;
    }

    private static final AtomicInteger idGen = new AtomicInteger(0);

    private static int nextId() {
        return idGen.incrementAndGet();
    }

    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb) {
        return sendRR(message, to, cb, message.getTimeout(), false);
    }

    public int sendRRWithFailure(MessageOut message, InetAddress to, IAsyncCallbackWithFailure cb) {
        return sendRR(message, to, cb, message.getTimeout(), true);
    }

    /**
     * Send a non-mutation message to a given endpoint. This method specifies a callback
     * which is invoked with the actual response.
     *
     * @param message message to be sent.
     * @param to      endpoint to which the message needs to be sent
     * @param cb      callback interface which is used to pass the responses or
     *                suggest that a timeout occurred to the invoker of the send().
     * @param timeout the timeout used for expiration
     * @return an reference to message id used to match with the result
     */
    public int sendRR(MessageOut message, InetAddress to, IAsyncCallback cb, long timeout, boolean failureCallback) {
        int id = addCallback(cb, message, to, timeout, failureCallback);
        sendOneWay(failureCallback ? message.withParameter(FAILURE_CALLBACK_PARAM, ONE_BYTE) : message, id, to);
        return id;
    }

    public void sendOneWay(MessageOut message, InetAddress to) {
        sendOneWay(message, nextId(), to);
    }

    public void sendReply(MessageOut message, int id, InetAddress to) {
        sendOneWay(message, id, to);
    }

    /**
     * Send a message to a given endpoint. This method adheres to the fire and forget
     * style messaging.
     *
     * @param message messages to be sent.
     * @param to      endpoint to which the message needs to be sent
     */
    public void sendOneWay(MessageOut message, int id, InetAddress to) {
        if (logger.isTraceEnabled())
            logger.trace("{} sending {} to {}@{}", FBUtilities.getBroadcastAddress(), message.verb, id, to);

        if (to.equals(FBUtilities.getBroadcastAddress()))
            logger.trace("Message-to-self {} going over MessagingService", message);

        // get pooled connection (really, connection queue)
        OutboundTcpConnection connection = getConnection(to, message);

        // write it
        connection.enqueue(message, id);
    }

    public <T> AsyncOneResponse<T> sendRR(MessageOut message, InetAddress to) {
        AsyncOneResponse<T> iar = new AsyncOneResponse<T>();
        sendRR(message, to, iar);
        return iar;
    }

    public void register(ILatencySubscriber subcriber) {
        subscribers.add(subcriber);
    }

    public void clearCallbacksUnsafe() {
        callbacks.reset();
    }

    /**
     * Wait for callbacks and don't allow any more to be created (since they could require writing hints)
     */
    public void shutdown() {
        logger.info("Waiting for messaging service to quiesce");

        // the important part
        callbacks.shutdownBlocking();

        // attempt to humor tests that try to stop and restart MS
        try {
            for (SocketThread th : socketThreads)
                th.close();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public void receive(MessageIn message, int id, long timestamp) {
        Runnable runnable = new MessageDeliveryTask(message, id, timestamp);
        LealoneExecutorService stage = StageManager.getStage(message.getMessageType());
        assert stage != null : "No stage for message type " + message.verb;

        stage.execute(runnable);
    }

    public void setCallbackForTests(int messageId, CallbackInfo callback) {
        callbacks.put(messageId, callback);
    }

    public CallbackInfo getRegisteredCallback(int messageId) {
        return callbacks.get(messageId);
    }

    public CallbackInfo removeRegisteredCallback(int messageId) {
        return callbacks.remove(messageId);
    }

    /**
     * @return System.nanoTime() when callback was created.
     */
    public long getRegisteredCallbackAge(int messageId) {
        return callbacks.getAge(messageId);
    }

    public static void validateMagic(int magic) throws IOException {
        if (magic != PROTOCOL_MAGIC)
            throw new IOException("invalid protocol header");
    }

    public static int getBits(int packed, int start, int count) {
        return packed >>> (start + 1) - count & ~(-1 << count);
    }

    public boolean areAllNodesAtLeast21() {
        return allNodesAtLeast21;
    }

    /**
     * @return the last version associated with address, or @param version if this is the first such version
     */
    public int setVersion(InetAddress endpoint, int version) {
        logger.debug("Setting version {} for {}", version, endpoint);
        if (version < VERSION_21)
            allNodesAtLeast21 = false;
        Integer v = versions.put(endpoint, version);

        // if the version was increased to 2.0 or later, see if all nodes are >= 2.0 now
        if (v != null && v < VERSION_21 && version >= VERSION_21)
            refreshAllNodesAtLeast21();

        return v == null ? version : v;
    }

    public void resetVersion(InetAddress endpoint) {
        logger.debug("Resetting version for {}", endpoint);
        Integer removed = versions.remove(endpoint);
        if (removed != null && removed <= VERSION_21)
            refreshAllNodesAtLeast21();
    }

    private void refreshAllNodesAtLeast21() {
        for (Integer version : versions.values()) {
            if (version < VERSION_21) {
                allNodesAtLeast21 = false;
                return;
            }
        }
        allNodesAtLeast21 = true;
    }

    public int getVersion(InetAddress endpoint) {
        Integer v = versions.get(endpoint);
        if (v == null) {
            // we don't know the version. assume current. we'll know soon enough if that was incorrect.
            logger.trace("Assuming current protocol version for {}", endpoint);
            return MessagingService.current_version;
        } else
            return Math.min(v, MessagingService.current_version);
    }

    @Override
    public int getVersion(String endpoint) throws UnknownHostException {
        return getVersion(InetAddress.getByName(endpoint));
    }

    public int getRawVersion(InetAddress endpoint) {
        Integer v = versions.get(endpoint);
        if (v == null)
            throw new IllegalStateException("getRawVersion() was called without checking knowsVersion() result first");
        return v;
    }

    public boolean knowsVersion(InetAddress endpoint) {
        return versions.containsKey(endpoint);
    }

    public void incrementDroppedMessages(Verb verb) {
        assert DROPPABLE_VERBS.contains(verb) : "Verb " + verb + " should not legally be dropped";
        droppedMessages.get(verb).dropped.mark();
    }

    private void logDroppedMessages() {
        for (Map.Entry<Verb, DroppedMessageMetrics> entry : droppedMessages.entrySet()) {
            int dropped = (int) entry.getValue().dropped.count();
            Verb verb = entry.getKey();
            int recent = dropped - lastDroppedInternal.get(verb);
            if (recent > 0) {
                logger.info("{} {} messages dropped in last {}ms", new Object[] { recent, verb,
                        LOG_DROPPED_INTERVAL_IN_MS });
                lastDroppedInternal.put(verb, dropped);
            }
        }
    }

    private static class SocketThread extends Thread {
        private final ServerSocket server;

        SocketThread(ServerSocket server, String name) {
            super(name);
            this.server = server;
        }

        @Override
        public void run() {
            while (!server.isClosed()) {
                Socket socket = null;
                try {
                    socket = server.accept();
                    if (!authenticate(socket)) {
                        logger.debug("remote failed to authenticate");
                        socket.close();
                        continue;
                    }

                    socket.setKeepAlive(true);
                    socket.setSoTimeout(2 * OutboundTcpConnection.WAIT_FOR_VERSION_MAX_TIME);
                    // determine the connection type to decide whether to buffer
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    MessagingService.validateMagic(in.readInt());
                    int header = in.readInt();
                    // isStream
                    // MessagingService.getBits(header, 3, 1);
                    int version = MessagingService.getBits(header, 15, 8);
                    if (logger.isDebugEnabled())
                        logger.debug("Connection version {} from {}", version, socket.getInetAddress());
                    socket.setSoTimeout(0);

                    Thread thread = new IncomingTcpConnection(version, MessagingService.getBits(header, 2, 1) == 1,
                            socket);
                    thread.start();
                } catch (AsynchronousCloseException e) {
                    // this happens when another thread calls close().
                    logger.debug("Asynchronous close seen by server thread");
                    break;
                } catch (ClosedChannelException e) {
                    logger.debug("MessagingService server thread already closed");
                    break;
                } catch (IOException e) {
                    logger.debug("Error reading the socket " + socket, e);
                    FileUtils.closeQuietly(socket);
                }
            }
            logger.info("MessagingService has terminated the accept() thread");
        }

        void close() throws IOException {
            logger.debug("Closing accept() thread");
            server.close();
        }

        private boolean authenticate(Socket socket) {
            return DatabaseDescriptor.getInternodeAuthenticator().authenticate(socket.getInetAddress(),
                    socket.getPort());
        }
    }

    @Override
    public Map<String, Integer> getCommandPendingTasks() {
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().cmdCon.getPendingMessages());
        return pendingTasks;
    }

    public int getCommandPendingTasks(InetAddress address) {
        OutboundTcpConnectionPool connection = connectionManagers.get(address);
        return connection == null ? 0 : connection.cmdCon.getPendingMessages();
    }

    @Override
    public Map<String, Long> getCommandCompletedTasks() {
        Map<String, Long> completedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().cmdCon.getCompletedMesssages());
        return completedTasks;
    }

    @Override
    public Map<String, Long> getCommandDroppedTasks() {
        Map<String, Long> droppedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            droppedTasks.put(entry.getKey().getHostAddress(), entry.getValue().cmdCon.getDroppedMessages());
        return droppedTasks;
    }

    @Override
    public Map<String, Integer> getResponsePendingTasks() {
        Map<String, Integer> pendingTasks = new HashMap<String, Integer>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            pendingTasks.put(entry.getKey().getHostAddress(), entry.getValue().ackCon.getPendingMessages());
        return pendingTasks;
    }

    @Override
    public Map<String, Long> getResponseCompletedTasks() {
        Map<String, Long> completedTasks = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet())
            completedTasks.put(entry.getKey().getHostAddress(), entry.getValue().ackCon.getCompletedMesssages());
        return completedTasks;
    }

    @Override
    public Map<String, Integer> getDroppedMessages() {
        Map<String, Integer> map = new HashMap<String, Integer>(droppedMessages.size());
        for (Map.Entry<Verb, DroppedMessageMetrics> entry : droppedMessages.entrySet())
            map.put(entry.getKey().toString(), (int) entry.getValue().dropped.count());
        return map;
    }

    @Override
    public Map<String, Integer> getRecentlyDroppedMessages() {
        Map<String, Integer> map = new HashMap<String, Integer>(droppedMessages.size());
        for (Map.Entry<Verb, DroppedMessageMetrics> entry : droppedMessages.entrySet())
            map.put(entry.getKey().toString(), entry.getValue().getRecentlyDropped());
        return map;
    }

    @Override
    public long getTotalTimeouts() {
        return ConnectionMetrics.totalTimeouts.count();
    }

    @Override
    public long getRecentTotalTimouts() {
        return ConnectionMetrics.getRecentTotalTimeout();
    }

    @Override
    public Map<String, Long> getTimeoutsPerHost() {
        Map<String, Long> result = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet()) {
            String ip = entry.getKey().getHostAddress();
            long recent = entry.getValue().getTimeouts();
            result.put(ip, recent);
        }
        return result;
    }

    @Override
    public Map<String, Long> getRecentTimeoutsPerHost() {
        Map<String, Long> result = new HashMap<String, Long>(connectionManagers.size());
        for (Map.Entry<InetAddress, OutboundTcpConnectionPool> entry : connectionManagers.entrySet()) {
            String ip = entry.getKey().getHostAddress();
            long recent = entry.getValue().getRecentTimeouts();
            result.put(ip, recent);
        }
        return result;
    }
}
