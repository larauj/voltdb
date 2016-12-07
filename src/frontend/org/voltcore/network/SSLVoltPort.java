/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltcore.network;

import com.google_voltpatches.common.util.concurrent.SettableFuture;
import org.voltcore.utils.DBBPool;
import org.voltcore.utils.DeferredSerialization;
import org.voltcore.utils.Pair;
import org.voltcore.utils.ssl.SSLBufferDecrypter;
import org.voltcore.utils.ssl.SSLBufferEncrypter;
import org.voltcore.utils.ssl.SSLEncryptionService;
import org.voltcore.utils.ssl.SSLMessageParser;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public class SSLVoltPort extends VoltPort {

    private final SSLEngine m_sslEngine;
    private final SSLBufferDecrypter m_sslBufferDecrypter;
    private final SSLBufferEncrypter m_sslBufferEncrypter;
    private DBBPool.BBContainer m_dstBufferCont;
    private final ByteBuffer m_dstBuffer;
    private final SSLMessageParser m_sslMessageParser;

    private final ByteBuffer m_frameHeader;
    private DBBPool.BBContainer m_frameCont;

    private int m_nextFrameLength = 0;

    private final DecryptionGateway m_decryptionGateway;
    private final EncryptionGateway m_encryptionGateway;
    private final ReadGateway m_readGateway;
    private final WriteGateway m_writeGateway;

    private final int m_appBufferSize;

    public SSLVoltPort(VoltNetwork network, InputHandler handler, InetSocketAddress remoteAddress, NetworkDBBPool pool, SSLEngine sslEngine) {
        super(network, handler, remoteAddress, pool);
        this.m_sslEngine = sslEngine;
        this.m_sslBufferDecrypter = new SSLBufferDecrypter(sslEngine);
        int appBufferSize = m_sslEngine.getSession().getApplicationBufferSize();
        // the app buffer size will sometimes be greater than 16k, but the ssl engine won't
        // encrypt more than 16k bytes at a time.  So it's simpler to not go over 16k.
        m_appBufferSize = Math.min(appBufferSize, 16 * 1024);
        int packetBufferSize = m_sslEngine.getSession().getPacketBufferSize();
        this.m_sslBufferEncrypter = new SSLBufferEncrypter(sslEngine, appBufferSize, packetBufferSize);
        this.m_writeGateway = new WriteGateway(network, this);
        this.m_encryptionGateway = new EncryptionGateway(m_sslBufferEncrypter, m_writeGateway, this);
        this.m_sslMessageParser = new SSLMessageParser();
        this.m_frameHeader = ByteBuffer.allocate(5);
        this.m_dstBufferCont = DBBPool.allocateDirect(packetBufferSize);
        this.m_dstBuffer = m_dstBufferCont.b();
        m_dstBuffer.clear();
        this.m_readGateway = new ReadGateway(network, this, handler);
        this.m_decryptionGateway = new DecryptionGateway(m_sslBufferDecrypter, m_readGateway, m_sslMessageParser, m_dstBuffer);
    }

    @Override
    public void run() throws IOException {
        int nRead = 0;
        try {
            final int maxRead = m_handler.getMaxRead();
            if (maxRead > 0) {
                nRead = fillReadStream(maxRead);
            }

            if (nRead > 0) {
                queueDecryptionTasks();
            }

            //Drain write now.
            boolean responsesReady = buildEncryptionTasks();

            if (nRead > 0 || responsesReady) {
                m_network.nudgeChannel(this);
            }

        } finally {
            if (m_encryptionGateway.isEmpty() && m_writeGateway.isEmpty()) {
                m_writeStream.checkBackpressureEnded();
            }

            synchronized (m_lock) {
                assert (m_running == true);
                m_running = false;
            }
        }
    }

    private boolean gatewaysEmpty() {
        return m_encryptionGateway.isEmpty() && m_writeGateway.isEmpty() && m_decryptionGateway.isEmpty() && m_readGateway.isEmpty();
    }

    private void queueDecryptionTasks() {
        int read;
        while (true) {
            if (m_nextFrameLength == 0) {
                read = readStream().getBytes(m_frameHeader);
                if (m_frameHeader.hasRemaining()) {
                    break;
                } else {
                    m_frameHeader.flip();
                    m_frameHeader.position(3);
                    m_nextFrameLength = m_frameHeader.getShort();
                    m_frameHeader.flip();
                    m_frameCont = DBBPool.allocateDirectAndPool(m_nextFrameLength + 5);
                    m_frameCont.b().clear();
                    m_frameCont.b().limit(m_nextFrameLength + 5);
                    m_frameCont.b().put(m_frameHeader);
                    m_frameHeader.clear();
                }
            }

            readStream().getBytes(m_frameCont.b());
            if (m_frameCont.b().hasRemaining()) {
                break;
            } else {
                m_decryptionGateway.enque(m_frameCont);
                m_nextFrameLength = 0;
            }
        }
    }

    /**
     * Swap the two queues of DeferredSerializations.  Serialize and create callables
     * to consume the resulting write buffers.
     * Similar functionality to NIOWriteStreamBase.swapAndSerializeQueuedWrites().
     * @param pool  The network byte buffer pool.
     * @return
     * @throws IOException
     */
    private boolean buildEncryptionTasks() throws IOException {
        final ArrayDeque<DeferredSerialization> oldlist = m_writeStream.getQueuedWrites();
        if (oldlist.isEmpty()) return false;
        DeferredSerialization ds = null;
        DBBPool.BBContainer outCont = null;
        while ((ds = oldlist.poll()) != null) {
            final int serializedSize = ds.getSerializedSize();
            if (serializedSize == DeferredSerialization.EMPTY_MESSAGE_LENGTH) continue;
            //Fastpath, serialize to direct buffer creating no garbage
            if (outCont == null) {
                outCont = m_pool.acquire();
                outCont.b().clear();
            }
            if (outCont.b().remaining() >= serializedSize) {
                final int oldLimit =  outCont.b().limit();
                outCont.b().limit( outCont.b().position() + serializedSize);
                final ByteBuffer slice =  outCont.b().slice();
                ds.serialize(slice);
                slice.position(0);
                outCont.b().position(outCont.b().limit());
                outCont.b().limit(oldLimit);
            } else {
                // first write out the current allocated container.
                if (outCont.b().position() > 0) {
                    outCont.b().flip();
                    m_encryptionGateway.enque(outCont);
                } else {
                    outCont.discard();
                }
                outCont = null;
                //Slow path serialize to heap, and then put in buffers
                ByteBuffer buf = ByteBuffer.allocate(serializedSize);
                ds.serialize(buf);
                buf.position(0);
                while (buf.hasRemaining()) {
                    if (buf.remaining() > m_appBufferSize) {
                        int oldLimit = buf.limit();
                        buf.limit(buf.position() + m_appBufferSize);
                        m_encryptionGateway.enque(DBBPool.wrapBB(buf.slice()));
                        buf.position(buf.position() + m_appBufferSize);
                        buf.limit(oldLimit);
                    } else {
                        m_encryptionGateway.enque(DBBPool.wrapBB(buf.slice()));
                        buf.position(buf.limit());
                    }
                }
            }
        }
        if (outCont != null) {
            if (outCont.b().position() > 0) {
                outCont.b().flip();
                m_encryptionGateway.enque(outCont);
            } else {
                outCont.discard();
                outCont = null;
            }
        }
        return true;
    }

    @Override
    void unregistered() {
        super.unregistered();
        m_dstBufferCont.discard();
        m_dstBufferCont = null;
    }

    private static class DecryptionGateway {
        private final SSLBufferDecrypter m_sslBufferDecrypter;
        private final SSLMessageParser m_sslMessageParser;
        private final ByteBuffer m_dstBuffer;
        private final Queue<Pair<DBBPool.BBContainer, SettableFuture<List<ByteBuffer>>>> m_q = new ArrayDeque<>();
        private final Queue<SettableFuture<List<ByteBuffer>>> m_results = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean m_hasOutstandingTask = new AtomicBoolean(false);
        private SettableFuture<List<ByteBuffer>> m_nextResult;
        private final ReadGateway m_readGateway;

        public DecryptionGateway(SSLBufferDecrypter m_sslBufferDecrypter, ReadGateway readGateway, SSLMessageParser sslMessageParser, ByteBuffer dstBuffer) {
            this.m_sslBufferDecrypter = m_sslBufferDecrypter;
            this.m_sslMessageParser = sslMessageParser;
            this.m_dstBuffer = dstBuffer;
            m_readGateway = readGateway;
        }

        void enque(final DBBPool.BBContainer srcCont) {
            SettableFuture<List<ByteBuffer>> fut = SettableFuture.create();
            if (srcCont.b().position() <= 0) return;

            synchronized (m_q) {
                m_q.add(new Pair<>(srcCont, fut));
            }
            if (m_hasOutstandingTask.compareAndSet(false, true)) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        Pair<DBBPool.BBContainer, SettableFuture<List<ByteBuffer>>> p;
                        synchronized (m_q) {
                            p = m_q.poll();
                        }
                        if (p != null) {
                            DBBPool.BBContainer srcC = p.getFirst();

                            SettableFuture<List<ByteBuffer>> f = p.getSecond();
                            List<ByteBuffer> messages = new ArrayList<>();
                            try {
                                ByteBuffer srcBuffer = srcC.b();
                                srcBuffer.flip();
                                m_dstBuffer.limit(m_dstBuffer.capacity());
                                m_sslBufferDecrypter.unwrap(srcBuffer, m_dstBuffer);
                                if (m_dstBuffer.hasRemaining()) {
                                    ByteBuffer message;
                                    while ((message = m_sslMessageParser.message(m_dstBuffer)) != null) {
                                        messages.add(message);
                                    }
                                }
                                m_dstBuffer.clear();
                                f.set(messages);
                                m_readGateway.enque(messages);
                            } catch (IOException e) {
                                f.setException(e);
                            } finally {
                                m_results.add(f);
                                srcC.discard();
                            }
                            synchronized (m_q) {
                                if (!m_q.isEmpty()) {
                                    SSLEncryptionService.instance().submitForDecryption(this);
                                } else {
                                    m_hasOutstandingTask.set(false);
                                }
                            }
                        }
                    }
                };
                SSLEncryptionService.instance().submitForDecryption(task);
            }
        }

        public List<ByteBuffer> getResult() throws IOException {
            if (m_nextResult == null && m_results.isEmpty()) {
                return null;
            }
            if (m_nextResult == null) {
                m_nextResult = m_results.poll();
            }
            if (m_nextResult.isDone()) {
                try {
                    List<ByteBuffer> result = m_nextResult.get();
                    m_nextResult = null;
                    return result;
                } catch (Exception e) {
                    throw new IOException("failed getting result of decryption task", e);
                }
            }
            return null;
        }

        public boolean isEmpty() {
            synchronized (m_q) {
                return m_q.isEmpty() && m_results.isEmpty() && m_nextResult == null;
            }
        }
    }

    private class EncryptionGateway {

        private final SSLBufferEncrypter m_sslBufferEncrypter;
        private final Queue<Pair<DBBPool.BBContainer, SettableFuture<EncryptionResult>>> m_q = new ArrayDeque<>();
        private final Queue<SettableFuture<EncryptionResult>> m_results = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean m_hasOutstandingTask = new AtomicBoolean(false);
        private SettableFuture<EncryptionResult> m_nextResult;
        private final WriteGateway m_writeGateway;
        private final VoltPort m_port;

        public EncryptionGateway(SSLBufferEncrypter m_sslBufferEncrypter, WriteGateway writeGateway, VoltPort port) {
            this.m_sslBufferEncrypter = m_sslBufferEncrypter;
            m_writeGateway = writeGateway;
            m_port = port;
        }

        void enque(final DBBPool.BBContainer fragmentCont) {
            SettableFuture<EncryptionResult> fut = SettableFuture.create();

            synchronized (m_q) {
                m_q.add(new Pair<>(fragmentCont, fut));
            }
            if (m_hasOutstandingTask.compareAndSet(false, true)) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        Pair<DBBPool.BBContainer, SettableFuture<EncryptionResult>> p;
                        synchronized (m_q) {
                            p = m_q.poll();
                        }
                        if (p != null) {
                            DBBPool.BBContainer fragCont = p.getFirst();
                            SettableFuture<EncryptionResult> f = p.getSecond();
                            try {
                                ByteBuffer fragment = fragCont.b();
                                DBBPool.BBContainer encCont = m_sslBufferEncrypter.encryptBuffer(fragment.slice());
                                EncryptionResult er = new EncryptionResult(encCont, encCont.b().remaining());
                                m_network.updateQueued(er.m_nBytesEncrypted, false, m_port);
                                m_writeGateway.enque(er);
                            } catch (IOException e) {
                                f.setException(e);
                            } finally {
                                m_results.add(f);
                                fragCont.discard();
                            }
                        }
                        synchronized (m_q) {
                            if (!m_q.isEmpty()) {
                                SSLEncryptionService.instance().submitForEncryption(this);
                            } else {
                                m_hasOutstandingTask.set(false);
                            }
                        }
                    }
                };
                SSLEncryptionService.instance().submitForEncryption(task);
            }
        }
        public EncryptionResult getResult() throws IOException {
            if (m_nextResult == null && m_results.isEmpty()) {
                return null;
            }
            if (m_nextResult == null) {
                m_nextResult = m_results.poll();
            }
            if (m_nextResult.isDone()) {
                try {
                    EncryptionResult result = m_nextResult.get();
                    m_nextResult = null;
                    return result;
                } catch (Exception e) {
                    throw new IOException("failed getting result of decryption task", e);
                }
            }
            return null;
        }

        public boolean isEmpty() {
            synchronized (m_q) {
                return m_q.isEmpty() && m_results.isEmpty() && m_nextResult == null;
            }
        }
    }

    private static class ReadGateway {

        private final Connection m_conn;
        private final InputHandler m_handler;
        private final Queue<Pair<List<ByteBuffer>, SettableFuture<Integer>>> m_q = new ArrayDeque<>();
        private final Queue<SettableFuture<Integer>> m_results = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean m_hasOutstandingTask = new AtomicBoolean(false);
        private SettableFuture<Integer> m_nextResult;
        private final VoltNetwork m_network;

        public ReadGateway(VoltNetwork network, Connection conn, InputHandler handler) {
            this.m_conn = conn;
            this.m_handler = handler;
            m_network = network;
        }

        void enque(List<ByteBuffer> messages) {
            SettableFuture<Integer> fut = SettableFuture.create();
            boolean checkOutstandingTask;
            synchronized (m_q) {
                m_q.add(new Pair<List<ByteBuffer>, SettableFuture<Integer>>(messages, fut));
                checkOutstandingTask = m_hasOutstandingTask.compareAndSet(false, true);
            }
            if (checkOutstandingTask) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        Pair<List<ByteBuffer>, SettableFuture<Integer>> p;
                        synchronized (m_q) {
                            p = m_q.poll();
                        }
                        if (p != null) {
                            int mCount = 0;
                            List<ByteBuffer> ms = p.getFirst();
                            SettableFuture<Integer> f = p.getSecond();
                            try {
                                for (ByteBuffer m : ms) {
                                    m_handler.handleMessage(m, m_conn);
                                    mCount++;
                                }
                                f.set(mCount);
                            } catch (IOException e) {
                                f.setException(e);
                            }
                            m_results.add(f);
                        }
                        synchronized (m_q) {
                            if (!m_q.isEmpty()) {
                                SSLEncryptionService.instance().submitForDecryption(this);
                            } else {
                                m_network.nudgeChannel(m_conn);
                                m_hasOutstandingTask.set(false);
                            }
                        }
                    }
                };
                SSLEncryptionService.instance().submitForDecryption(task);
            }
        }

        public Integer getResult() throws IOException {
            if (m_nextResult == null && m_results.isEmpty()) {
                return null;
            }
            if (m_nextResult == null) {
                m_nextResult = m_results.poll();
            }
            if (m_nextResult.isDone()) {
                try {
                    Integer result = m_nextResult.get();
                    m_nextResult = null;
                    return result;
                } catch (Exception e) {
                    throw new IOException("failed getting result of decryption task", e);
                }
            }
            return null;
        }

        public boolean isEmpty() {
            synchronized (m_q) {
                return m_q.isEmpty() && m_results.isEmpty() && m_nextResult == null;
            }
        }
    }

    private class WriteGateway {

        private final Queue<Pair<EncryptionResult, SettableFuture<WriteResult>>> m_q = new ArrayDeque<>();
        private final Queue<SettableFuture<WriteResult>> m_results = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean m_hasOutstandingTask = new AtomicBoolean(false);
        private SettableFuture<WriteResult> m_nextResult;
        private final VoltNetwork m_network;
        private final VoltPort m_port;

        public WriteGateway(VoltNetwork network, VoltPort connection) {
            m_network = network;
            m_port = connection;
        }

        void enque(EncryptionResult encRes) {
            assert m_channel != null;
            SettableFuture<WriteResult> fut = SettableFuture.create();
            boolean checkOutstandingTask;
            synchronized (m_q) {
                m_q.add(new Pair<EncryptionResult, SettableFuture<WriteResult>>(encRes, fut));
                checkOutstandingTask = m_hasOutstandingTask.compareAndSet(false, true);
            }
            if (checkOutstandingTask) {
                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        Pair<EncryptionResult, SettableFuture<WriteResult>> p;
                        synchronized (m_q) {
                            p = m_q.peek();
                        }
                        if (p != null) {
                            EncryptionResult er = p.getFirst();
                            SettableFuture<WriteResult> f = p.getSecond();
                            int bytesQueued = er.m_encCont.b().remaining();
                            boolean triedToDiscard = false;
                            try {
                                DBBPool.BBContainer writesCont = er.m_encCont;
                                int bytesWritten = m_channel.write(writesCont.b());
                                if (! writesCont.b().hasRemaining()) {
                                    synchronized (m_q) {
                                        m_q.poll();
                                    }
                                    triedToDiscard = true;
                                    er.m_encCont.discard();
                                    WriteResult wr = new WriteResult(bytesQueued, bytesWritten);
                                    f.set(new WriteResult(bytesQueued, bytesWritten));
                                    m_network.updateQueued(-wr.m_bytesWritten, false, m_port);
                                    if (wr.m_bytesWritten < wr.m_bytesQueued) {
                                        m_writeStream.checkBackpressureStarted();
                                    }
                                }
                            } catch (IOException e) {
                                if (!triedToDiscard) {
                                    er.m_encCont.discard();
                                }
                                f.setException(e);
                            }
                            m_results.add(f);
                        }
                        synchronized (m_q) {
                            if (!m_q.isEmpty()) {
                                SSLEncryptionService.instance().submitForEncryption(this);
                            } else {
                                m_port.disableWriteSelection();
                                m_network.nudgeChannel(m_port);
                                m_hasOutstandingTask.set(false);
                            }
                        }
                    }
                };
                SSLEncryptionService.instance().submitForEncryption(task);
            }
        }

        public WriteResult getResult() throws IOException {
            if (m_nextResult == null && m_results.isEmpty()) {
                return null;
            }
            if (m_nextResult == null) {
                m_nextResult = m_results.poll();
            }
            if (m_nextResult.isDone()) {
                try {
                    WriteResult result = m_nextResult.get();
                    m_nextResult = null;
                    return result;
                } catch (Exception e) {
                    throw new IOException("failed getting result of decryption task", e);
                }
            }
            return null;
        }

        public boolean isEmpty() {
            synchronized (m_q) {
                return m_q.isEmpty() && m_results.isEmpty() && m_nextResult == null;
            }
        }
    }

    public class EncryptionResult {
        public final DBBPool.BBContainer m_encCont;
        public final int m_nBytesEncrypted;
        public EncryptionResult(DBBPool.BBContainer encCont, int nBytesEncrypted) {
            this.m_encCont = encCont;
            this.m_nBytesEncrypted = nBytesEncrypted;
        }
    }

    public class WriteResult {
        public final int m_bytesQueued;
        public final int m_bytesWritten;
        public WriteResult(int bytesQueued, int bytesWritten) {
            this.m_bytesQueued = bytesQueued;
            this.m_bytesWritten = bytesWritten;
        }
    }
}
