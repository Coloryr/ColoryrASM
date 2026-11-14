package com.coloryr;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.util.concurrent.TimeUnit;

public class NettyClient implements AutoCloseable {

    private final String host;
    private final Integer port;
    private final NioEventLoopGroup group;
    private final Bootstrap bootstrap;
    private volatile Channel channel;

    public NettyClient(String host, Integer port) throws Exception {
        this.host = host;
        this.port = port;
        // 非 daemon 的事件循环会让游戏关掉后 JVM 一直不退出（26.3 的退出 watchdog 会报崩溃）
        this.group = new NioEventLoopGroup(1, runnable -> {
            Thread thread = new Thread(runnable, "coloryrasm-netty");
            thread.setDaemon(true);
            return thread;
        });
        this.bootstrap = setup();
        try {
            this.run();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private Bootstrap setup() {
        return new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new ColoryrNettyPackChannel());
                    }
                });
    }

    public void run() throws InterruptedException {
        channel = bootstrap.connect(host, port).sync().channel();
    }

    public void sendMessage(ByteBuf pack) {
        Channel current = channel;
        if (current != null && current.isWritable()) {
            current.writeAndFlush(pack);
        }
    }

    /** 关闭连接与事件循环；daemon 只是兜底，正常退出仍应走这里释放 socket */
    @Override
    public void close() {
        Channel current = channel;
        channel = null;
        if (current != null) {
            current.close();
        }
        group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
