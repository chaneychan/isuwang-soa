package com.isuwang.soa.container.netty;

import com.isuwang.soa.container.util.LoggerUtil;
import com.isuwang.soa.container.util.PlatformProcessDataFactory;
import com.isuwang.soa.core.*;
import com.isuwang.soa.monitor.api.domain.PlatformProcessData;
import com.isuwang.soa.registry.ConfigKey;
import com.isuwang.soa.registry.RegistryAgentProxy;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Soa Server Handler
 *
 * @author craneding
 * @date 16/1/12
 */
public class SoaServerHandler extends ChannelHandlerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoaServerHandler.class);
    private static final Logger SIMPLE_LOGGER = LoggerFactory.getLogger(LoggerUtil.SIMPLE_LOG);

    private static Map<ProcessorKey, SoaBaseProcessor<?>> soaProcessors;

    private final Boolean useThreadPool = SoaSystemEnvProperties.SOA_CONTAINER_USETHREADPOOL;

    static class ServerThreadFactory implements ThreadFactory {
        private static final AtomicInteger poolNumber = new AtomicInteger(1);
        private final ThreadGroup group;
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        ServerThreadFactory() {
            SecurityManager s = System.getSecurityManager();
            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();
            namePrefix = "trans-pool-" + poolNumber.getAndIncrement() + "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);
            if (t.isDaemon())
                t.setDaemon(false);
            if (t.getPriority() != Thread.NORM_PRIORITY)
                t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    private volatile static ExecutorService executorService = Executors.newFixedThreadPool(SoaSystemEnvProperties.SOA_CORE_POOL_SIZE, new ServerThreadFactory());

    public SoaServerHandler(Map<ProcessorKey, SoaBaseProcessor<?>> soaProcessors) {
        this.soaProcessors = soaProcessors;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        readRequestHeader(ctx, (ByteBuf) msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOGGER.error(cause.getMessage(), cause);

        ctx.close();
    }

    protected void readRequestHeader(ChannelHandlerContext ctx, ByteBuf inputBuf) throws TException {
        TSoaTransport inputSoaTransport = null;

        boolean intoPool = false, intoProcessRequest = false;
        try {
            final Long startTime = System.currentTimeMillis();

            final int requestLength = getRequestLength(inputBuf);

            final TransactionContext context = TransactionContext.Factory.getNewInstance();
            final SoaHeader soaHeader = new SoaHeader();
            inputSoaTransport = new TSoaTransport(inputBuf);
            context.setHeader(soaHeader);
            TransactionContext.Factory.setCurrentInstance(context);

            final PlatformProcessData processData = PlatformProcessDataFactory.getNewInstance(soaHeader);
            processData.setRequestFlow(requestLength + Integer.BYTES);
            PlatformProcessDataFactory.setCurrentInstance(processData);

            final TSoaServiceProtocol inputProtocol = new TSoaServiceProtocol(inputSoaTransport, false);
            TMessage tMessage = inputProtocol.readMessageBegin();
            context.setSeqid(tMessage.seqid);

            /**
             * check if use executorService for this service and
             */
            boolean b = true;

            String serviceKey = soaHeader.getServiceName() + "." + soaHeader.getVersionName() + "." + soaHeader.getMethodName() + ".producer";
            Map<ConfigKey, Object> configs = RegistryAgentProxy.getCurrentInstance(RegistryAgentProxy.Type.Server).getConfig().get(serviceKey);

            if (null != configs) {
                Boolean aBoolean = (Boolean) configs.get(ConfigKey.ThreadPool);

                if (aBoolean != null)
                    b = aBoolean.booleanValue();
            }

            intoProcessRequest = true;

            if (useThreadPool && b) {
                final TSoaTransport finalInputSoaTransport = inputSoaTransport;
                executorService.execute(() -> processRequest(ctx, inputBuf, finalInputSoaTransport, inputProtocol, context, startTime, processData));
                intoPool = true;
            } else
                processRequest(ctx, inputBuf, inputSoaTransport, inputProtocol, context, startTime, processData);
        } finally {
            if (!intoPool) {
                if (inputSoaTransport.isOpen())
                    inputSoaTransport.close();
            }

            if (!intoProcessRequest)
                inputBuf.release();

            TransactionContext.Factory.removeCurrentInstance();
            PlatformProcessDataFactory.removeCurrentInstance();
        }
    }

    private int getRequestLength(ByteBuf inputBuf) {
        int readerIndex = inputBuf.readerIndex();
        int requestLength = inputBuf.readInt();
        inputBuf.readerIndex(readerIndex);
        return requestLength;
    }

    protected void processRequest(ChannelHandlerContext ctx, ByteBuf inputBuf, TSoaTransport inputSoaTransport, TSoaServiceProtocol inputProtocol, TransactionContext context, Long startTime, PlatformProcessData processData) {
        final ByteBuf outputBuf = ctx.alloc().buffer(8192);

        TransactionContext.Factory.setCurrentInstance(context);
        PlatformProcessDataFactory.setCurrentInstance(processData);

        SoaHeader soaHeader = context.getHeader();

        final TSoaTransport outputSoaTransport = new TSoaTransport(outputBuf);
        TSoaServiceProtocol outputProtocol = null;

        boolean isSucceed = false;

        String responseCode = "-", responseMsg = "-";
        try {
            outputProtocol = new TSoaServiceProtocol(outputSoaTransport, false);
            SoaBaseProcessor<?> soaProcessor = soaProcessors.get(new ProcessorKey(soaHeader.getServiceName(), soaHeader.getVersionName()));

            if (soaProcessor == null) {
                throw new SoaException(SoaBaseCode.NotFoundServer);
            }

            soaProcessor.process(inputProtocol, outputProtocol);

            outputSoaTransport.flush();

            ctx.writeAndFlush(outputBuf);

            isSucceed = true;

            if (soaHeader.getRespCode().isPresent())
                responseCode = soaHeader.getRespCode().get();
            if (soaHeader.getRespMessage().isPresent())
                responseMsg = soaHeader.getRespMessage().get();
        } catch (SoaException e) {
            LOGGER.error(e.getMessage(), e);

            writeErrorMessage(ctx, outputBuf, context, soaHeader, outputSoaTransport, outputProtocol, e);

            responseCode = e.getCode();
            responseMsg = e.getMsg();
        } catch (Throwable e) {
            LOGGER.error(e.getMessage(), e);

            writeErrorMessage(ctx, outputBuf, context, soaHeader, outputSoaTransport, outputProtocol, new SoaException(SoaBaseCode.UnKnown));

            responseCode = SoaBaseCode.UnKnown.getCode();
            responseMsg = SoaBaseCode.UnKnown.getMsg();
        } finally {
            if (inputSoaTransport != null)
                inputSoaTransport.close();

            if (outputSoaTransport != null)
                outputSoaTransport.close();

            //LEAK: ByteBuf.release() was not called before it's garbage-collected. Enable advanced leak reporting to find out where the leak occurred. To enable advanced leak reporting, specify the JVM option '-Dio.netty.leakDetectionLevel=advanced' or call ResourceLeakDetector.setLevel() See http://netty.io/wiki/reference-counted-objects.html for more information.
            // to see SoaDecoder: ByteBuf msg = in.slice(readerIndex, length + Integer.BYTES).retain();
            inputBuf.release();

            final boolean finalIsSucceed = isSucceed;
            final String finalResponseCode = responseCode;
            final String finalResponseMsg = responseMsg;
            PlatformProcessDataFactory.update(soaHeader, cacheProcessData -> {
                long totalTime = System.currentTimeMillis() - startTime;

                if (cacheProcessData.getPMinTime() == 0 || totalTime < cacheProcessData.getPMinTime())
                    cacheProcessData.setPMinTime(totalTime);
                if (cacheProcessData.getPMaxTime() == 0 || totalTime > cacheProcessData.getPMaxTime())
                    cacheProcessData.setPMaxTime(totalTime);
                cacheProcessData.setPTotalTime(cacheProcessData.getPTotalTime() + totalTime);

                if (finalIsSucceed)
                    cacheProcessData.setSucceedCalls(cacheProcessData.getSucceedCalls() + 1);
                else
                    cacheProcessData.setFailCalls(cacheProcessData.getFailCalls() + 1);

                cacheProcessData.setTotalCalls(cacheProcessData.getTotalCalls() + 1);

                cacheProcessData.setRequestFlow(cacheProcessData.getRequestFlow() + processData.getRequestFlow());
                cacheProcessData.setResponseFlow(cacheProcessData.getResponseFlow() + outputBuf.writerIndex());

                StringBuilder builder = new StringBuilder("DONE")
                        .append(" ").append(ctx.channel().remoteAddress())
                        .append(" ").append(ctx.channel().localAddress())
                        .append(" ").append(context.getSeqid())
                        .append(" ").append(soaHeader.getServiceName()).append(".").append(soaHeader.getMethodName()).append(":").append(soaHeader.getVersionName())
                        .append(" ").append(finalResponseCode)
                        .append(" ").append(finalResponseMsg)
                        .append(" ").append(processData.getRequestFlow())
                        .append(" ").append(outputBuf.writerIndex())
                        .append(" ").append(totalTime);
                SIMPLE_LOGGER.info(builder.toString());
            });

            TransactionContext.Factory.removeCurrentInstance();
            PlatformProcessDataFactory.removeCurrentInstance();
        }
    }

    private void writeErrorMessage(ChannelHandlerContext ctx, ByteBuf outputBuf, TransactionContext context, SoaHeader soaHeader, TSoaTransport outputSoaTransport, TSoaServiceProtocol outputProtocol, SoaException e) {
        if (outputProtocol != null) {
            try {
                soaHeader.setRespCode(Optional.of(e.getCode()));
                soaHeader.setRespMessage(Optional.of(e.getMsg()));
                outputProtocol.writeMessageBegin(new TMessage(soaHeader.getServiceName() + ":" + soaHeader.getMethodName(), TMessageType.REPLY, context.getSeqid()));
                outputProtocol.writeMessageEnd();

                outputSoaTransport.flush();

                ctx.writeAndFlush(outputBuf);

                LOGGER.info("{} {} {} response header:{} body:{null}", soaHeader.getServiceName(), soaHeader.getVersionName(), soaHeader.getMethodName(), soaHeader.toString());
            } catch (Throwable e1) {
                LOGGER.error(e1.getMessage(), e1);
            }
        }
    }
}
