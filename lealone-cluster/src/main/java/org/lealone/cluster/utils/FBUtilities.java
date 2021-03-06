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
package org.lealone.cluster.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.Checksum;

import org.apache.commons.lang3.StringUtils;
import org.lealone.cluster.config.DatabaseDescriptor;
import org.lealone.cluster.db.DecoratedKey;
import org.lealone.cluster.dht.IPartitioner;
import org.lealone.cluster.dht.Range;
import org.lealone.cluster.dht.Token;
import org.lealone.cluster.exceptions.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;

public class FBUtilities {
    private static final Logger logger = LoggerFactory.getLogger(FBUtilities.class);
    public static final BigInteger TWO = new BigInteger("2");
    private static final String DEFAULT_TRIGGER_DIR = "triggers";

    private static volatile InetAddress localInetAddress;
    private static volatile InetAddress broadcastInetAddress;

    private static final boolean isWindows = System.getProperty("os.name").startsWith("Windows");

    public static int getAvailableProcessors() {
        if (System.getProperty("lealone.available_processors") != null)
            return Integer.parseInt(System.getProperty("lealone.available_processors"));
        else
            return Runtime.getRuntime().availableProcessors();
    }

    private static final ThreadLocal<MessageDigest> localMD5Digest = new ThreadLocal<MessageDigest>() {
        @Override
        protected MessageDigest initialValue() {
            return newMessageDigest("MD5");
        }

        @Override
        public MessageDigest get() {
            MessageDigest digest = super.get();
            digest.reset();
            return digest;
        }
    };

    public static final int MAX_UNSIGNED_SHORT = 0xFFFF;

    public static MessageDigest threadLocalMD5Digest() {
        return localMD5Digest.get();
    }

    public static MessageDigest newMessageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException nsae) {
            throw new RuntimeException("the requested digest algorithm (" + algorithm + ") is not available", nsae);
        }
    }

    /**
     * Please use getBroadcastAddress instead. You need this only when you have to listen/connect.
     */
    public static InetAddress getLocalAddress() {
        if (localInetAddress == null)
            try {
                localInetAddress = DatabaseDescriptor.getListenAddress() == null ? InetAddress.getLocalHost()
                        : DatabaseDescriptor.getListenAddress();
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        return localInetAddress;
    }

    public static InetAddress getBroadcastAddress() {
        if (broadcastInetAddress == null)
            broadcastInetAddress = DatabaseDescriptor.getBroadcastAddress() == null ? getLocalAddress()
                    : DatabaseDescriptor.getBroadcastAddress();
        return broadcastInetAddress;
    }

    public static Collection<InetAddress> getAllLocalAddresses() {
        Set<InetAddress> localAddresses = new HashSet<InetAddress>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            if (nets != null) {
                while (nets.hasMoreElements())
                    localAddresses.addAll(Collections.list(nets.nextElement().getInetAddresses()));
            }
        } catch (SocketException e) {
            throw new AssertionError(e);
        }
        return localAddresses;
    }

    /**
     * Given two bit arrays represented as BigIntegers, containing the given
     * number of significant bits, calculate a midpoint.
     *
     * @param left The left point.
     * @param right The right point.
     * @param sigbits The number of bits in the points that are significant.
     * @return A midpoint that will compare bitwise halfway between the params, and
     * a boolean representing whether a non-zero lsbit remainder was generated.
     */
    public static Pair<BigInteger, Boolean> midpoint(BigInteger left, BigInteger right, int sigbits) {
        BigInteger midpoint;
        boolean remainder;
        if (left.compareTo(right) < 0) {
            BigInteger sum = left.add(right);
            remainder = sum.testBit(0);
            midpoint = sum.shiftRight(1);
        } else {
            BigInteger max = TWO.pow(sigbits);
            // wrapping case
            BigInteger distance = max.add(right).subtract(left);
            remainder = distance.testBit(0);
            midpoint = distance.shiftRight(1).add(left).mod(max);
        }
        return Pair.create(midpoint, remainder);
    }

    public static int compareUnsigned(byte[] bytes1, byte[] bytes2, int offset1, int offset2, int len1, int len2) {
        return FastByteOperations.compareUnsigned(bytes1, offset1, len1, bytes2, offset2, len2);
    }

    public static int compareUnsigned(byte[] bytes1, byte[] bytes2) {
        return compareUnsigned(bytes1, bytes2, 0, 0, bytes1.length, bytes2.length);
    }

    /**
     * @return The bitwise XOR of the inputs. The output will be the same length as the
     * longer input, but if either input is null, the output will be null.
     */
    public static byte[] xor(byte[] left, byte[] right) {
        if (left == null || right == null)
            return null;
        if (left.length > right.length) {
            byte[] swap = left;
            left = right;
            right = swap;
        }

        // left.length is now <= right.length
        byte[] out = Arrays.copyOf(right, right.length);
        for (int i = 0; i < left.length; i++) {
            out[i] = (byte) ((left[i] & 0xFF) ^ (right[i] & 0xFF));
        }
        return out;
    }

    public static byte[] hash(ByteBuffer... data) {
        MessageDigest messageDigest = localMD5Digest.get();
        for (ByteBuffer block : data) {
            if (block.hasArray())
                messageDigest.update(block.array(), block.arrayOffset() + block.position(), block.remaining());
            else
                messageDigest.update(block.duplicate());
        }

        return messageDigest.digest();
    }

    public static BigInteger hashToBigInteger(ByteBuffer data) {
        return new BigInteger(hash(data)).abs();
    }

    public static void sortSampledKeys(List<DecoratedKey> keys, Range<Token> range) {
        if (range.left.compareTo(range.right) >= 0) {
            // range wraps.  have to be careful that we sort in the same order as the range to find the right midpoint.
            final Token right = range.right;
            Comparator<DecoratedKey> comparator = new Comparator<DecoratedKey>() {
                @Override
                public int compare(DecoratedKey o1, DecoratedKey o2) {
                    if ((right.compareTo(o1.getToken()) < 0 && right.compareTo(o2.getToken()) < 0)
                            || (right.compareTo(o1.getToken()) > 0 && right.compareTo(o2.getToken()) > 0)) {
                        // both tokens are on the same side of the wrap point
                        return o1.compareTo(o2);
                    }
                    return o2.compareTo(o1);
                }
            };
            Collections.sort(keys, comparator);
        } else {
            // unwrapped range (left < right).  standard sort is all we need.
            Collections.sort(keys);
        }
    }

    public static String resourceToFile(String filename) throws ConfigurationException {
        ClassLoader loader = FBUtilities.class.getClassLoader();
        URL scpurl = loader.getResource(filename);
        if (scpurl == null)
            throw new ConfigurationException("unable to locate " + filename);

        return new File(scpurl.getFile()).getAbsolutePath();
    }

    public static File lealoneTriggerDir() {
        File triggerDir = null;
        if (System.getProperty("lealone.triggers_dir") != null) {
            triggerDir = new File(System.getProperty("lealone.triggers_dir"));
        } else {
            URL confDir = FBUtilities.class.getClassLoader().getResource(DEFAULT_TRIGGER_DIR);
            if (confDir != null)
                triggerDir = new File(confDir.getFile());
        }
        if (triggerDir == null || !triggerDir.exists()) {
            logger.warn("Trigger directory doesn't exist, please create it and try again.");
            return null;
        }
        return triggerDir;
    }

    public static String getReleaseVersionString() {
        InputStream in = null;
        try {
            in = FBUtilities.class.getClassLoader().getResourceAsStream("org/lealone/res/version.properties");
            if (in == null) {
                return System.getProperty("lealone.releaseVersion", "Unknown");
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("lealoneVersion");
        } catch (Exception e) {
            JVMStabilityInspector.inspectThrowable(e);
            logger.warn("Unable to load version.properties", e);
            return "debug version";
        } finally {
            FileUtils.closeQuietly(in);
        }
    }

    public static long timestampMicros() {
        // we use microsecond resolution for compatibility with other client libraries, even though
        // we can't actually get microsecond precision.
        return System.currentTimeMillis() * 1000;
    }

    public static void waitOnFutures(Iterable<Future<?>> futures) {
        for (Future<?> f : futures)
            waitOnFuture(f);
    }

    public static <T> T waitOnFuture(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException ee) {
            throw new RuntimeException(ee);
        } catch (InterruptedException ie) {
            throw new AssertionError(ie);
        }
    }

    public static IPartitioner newPartitioner(String partitionerClassName) throws ConfigurationException {
        if (!partitionerClassName.contains("."))
            partitionerClassName = "org.lealone.cluster.dht." + partitionerClassName;
        return FBUtilities.instanceOrConstruct(partitionerClassName, "partitioner");
    }

    /**
     * @return The Class for the given name.
     * @param classname Fully qualified classname.
     * @param readable Descriptive noun for the role the class plays.
     * @throws ConfigurationException If the class cannot be found.
     */
    @SuppressWarnings("unchecked")
    public static <T> Class<T> classForName(String classname, String readable) throws ConfigurationException {
        try {
            return (Class<T>) Class.forName(classname);
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException(String.format("Unable to find %s class '%s'", readable, classname), e);
        } catch (NoClassDefFoundError e) {
            throw new ConfigurationException(String.format("Unable to find %s class '%s'", readable, classname), e);
        }
    }

    /**
     * Constructs an instance of the given class, which must have a no-arg or default constructor.
     * @param classname Fully qualified classname.
     * @param readable Descriptive noun for the role the class plays.
     * @throws ConfigurationException If the class cannot be found.
     */
    public static <T> T instanceOrConstruct(String classname, String readable) throws ConfigurationException {
        Class<T> cls = FBUtilities.classForName(classname, readable);
        try {
            Field instance = cls.getField("instance");
            return cls.cast(instance.get(null));
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            // Could not get instance field. Try instantiating.
            return construct(cls, classname, readable);
        }
    }

    /**
     * Constructs an instance of the given class, which must have a no-arg or default constructor.
     * @param classname Fully qualified classname.
     * @param readable Descriptive noun for the role the class plays.
     * @throws ConfigurationException If the class cannot be found.
     */
    public static <T> T construct(String classname, String readable) throws ConfigurationException {
        Class<T> cls = FBUtilities.classForName(classname, readable);
        return construct(cls, classname, readable);
    }

    private static <T> T construct(Class<T> cls, String classname, String readable) throws ConfigurationException {
        try {
            return cls.newInstance();
        } catch (IllegalAccessException e) {
            throw new ConfigurationException(String.format("Default constructor for %s class '%s' is inaccessible.",
                    readable, classname));
        } catch (InstantiationException e) {
            throw new ConfigurationException(
                    String.format("Cannot use abstract class '%s' as %s.", classname, readable));
        } catch (Exception e) {
            // Catch-all because Class.newInstance() "propagates any exception thrown by the nullary constructor, including a checked exception".
            if (e.getCause() instanceof ConfigurationException)
                throw (ConfigurationException) e.getCause();
            throw new ConfigurationException(String.format("Error instantiating %s class '%s'.", readable, classname),
                    e);
        }
    }

    public static <T> SortedSet<T> singleton(T column, Comparator<? super T> comparator) {
        SortedSet<T> s = new TreeSet<T>(comparator);
        s.add(column);
        return s;
    }

    public static String toString(Map<?, ?> map) {
        Joiner.MapJoiner joiner = Joiner.on(", ").withKeyValueSeparator(":");
        return joiner.join(map);
    }

    /**
     * Used to get access to protected/private field of the specified class
     * @param klass - name of the class
     * @param fieldName - name of the field
     * @return Field or null on error
     */
    public static Field getProtectedField(Class<?> klass, String fieldName) {
        Field field;

        try {
            field = klass.getDeclaredField(fieldName);
            field.setAccessible(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        return field;
    }

    /**
     * Starts and waits for the given @param pb to finish.
     * @throws java.io.IOException on non-zero exit code
     */
    public static void exec(ProcessBuilder pb) throws IOException {
        Process p = pb.start();
        try {
            int errCode = p.waitFor();
            if (errCode != 0) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String lineSep = System.getProperty("line.separator");
                    StringBuilder sb = new StringBuilder();
                    String str;
                    while ((str = in.readLine()) != null)
                        sb.append(str).append(lineSep);
                    while ((str = err.readLine()) != null)
                        sb.append(str).append(lineSep);
                    throw new IOException("Exception while executing the command: "
                            + StringUtils.join(pb.command(), " ") + ", command error Code: " + errCode
                            + ", command output: " + sb.toString());
                }
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    public static void updateChecksumInt(Checksum checksum, int v) {
        checksum.update((v >>> 24) & 0xFF);
        checksum.update((v >>> 16) & 0xFF);
        checksum.update((v >>> 8) & 0xFF);
        checksum.update((v >>> 0) & 0xFF);
    }

    public static long abs(long index) {
        long negbit = index >> 63;
        return (index ^ negbit) - negbit;
    }

    public static long copy(InputStream from, OutputStream to, long limit) throws IOException {
        byte[] buffer = new byte[64]; // 64 byte buffer
        long copied = 0;
        int toCopy = buffer.length;
        while (true) {
            if (limit < buffer.length + copied)
                toCopy = (int) (limit - copied);
            int sofar = from.read(buffer, 0, toCopy);
            if (sofar == -1)
                break;
            to.write(buffer, 0, sofar);
            copied += sofar;
            if (limit == copied)
                break;
        }
        return copied;
    }

    public static boolean isUnix() {
        return !isWindows;
    }

    public static void updateWithShort(MessageDigest digest, int val) {
        digest.update((byte) ((val >> 8) & 0xFF));
        digest.update((byte) (val & 0xFF));
    }

    public static void updateWithByte(MessageDigest digest, int val) {
        digest.update((byte) (val & 0xFF));
    }

    public static void updateWithInt(MessageDigest digest, int val) {
        digest.update((byte) ((val >>> 24) & 0xFF));
        digest.update((byte) ((val >>> 16) & 0xFF));
        digest.update((byte) ((val >>> 8) & 0xFF));
        digest.update((byte) ((val >>> 0) & 0xFF));
    }

    public static void updateWithLong(MessageDigest digest, long val) {
        digest.update((byte) ((val >>> 56) & 0xFF));
        digest.update((byte) ((val >>> 48) & 0xFF));
        digest.update((byte) ((val >>> 40) & 0xFF));
        digest.update((byte) ((val >>> 32) & 0xFF));
        digest.update((byte) ((val >>> 24) & 0xFF));
        digest.update((byte) ((val >>> 16) & 0xFF));
        digest.update((byte) ((val >>> 8) & 0xFF));
        digest.update((byte) ((val >>> 0) & 0xFF));
    }
}
