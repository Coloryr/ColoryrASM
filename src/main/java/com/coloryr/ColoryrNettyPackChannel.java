package com.coloryr;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * 与启动器的 netty 连接
 *
 * 协议没有总长度前缀，TCP 可能把多条消息粘在一次读取里、也可能只读到半条，
 * 所以这里自己累积数据，按消息类型推算边界后再交给 {@link ColoryrPackBuilder}。
 *
 * 连接建立后启动器先下发连接挑战，本端用启动参数里的密钥算 HMAC-SHA256 发回；
 * 收到「验证结果」才算通过，之后才注册游戏通道并上报初始鼠标状态。
 * 应答不对时启动器不回「验证结果」而是直接断开，本端等不到就断开连接。
 */
public class ColoryrNettyPackChannel extends ChannelInboundHandlerAdapter {
    /** 累积数据的上限，单位字节；超过仍拼不出消息说明数据异常 */
    private static final int MAX_BUFFER_SIZE = 1 << 20;

    /** 握手阶段：等启动器下发的挑战串 */
    private static final int STEP_CHALLENGE = 0;
    /** 握手阶段：应答已发，等「验证结果」 */
    private static final int STEP_RESULT = 1;
    /** 握手完成，开始收普通消息 */
    private static final int STEP_READY = 2;

    /** 跨读取累积的字节 */
    private final ByteBuf buffer = Unpooled.buffer();

    /** 当前握手阶段 */
    private int step = STEP_CHALLENGE;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        ByteBuf in = (ByteBuf) msg;
        try {
            buffer.writeBytes(in);
        } finally {
            in.release();
        }

        while (true) {
            int length;
            try {
                length = ColoryrPackBuilder.packLength(buffer);
            } catch (Throwable e) {
                buffer.clear();
                return;
            }
            if (length == 0) {
                break;
            }
            if (length < 0) {
                System.out.println("[ColoryrASM] invalid pack length, drop received data");
                buffer.clear();
                return;
            }

            ByteBuf frame = buffer.readRetainedSlice(length);
            try {
                int type = frame.getInt(frame.readerIndex());
                if (step != STEP_READY) {
                    if (step == STEP_CHALLENGE) {
                        if (type != ColoryrPackBuilder.TypeGameChallenge) {
                            System.out.println("[ColoryrASM] handshake failed: challenge expected, got " + type);
                            ctx.close();
                            return;
                        }
                        frame.skipBytes(4);
                        String nonce = ColoryrPackBuilder.readString(frame);
                        ByteBuf proof = ColoryrPackBuilder.buildAuthProof(nonce);
                        if (proof == null) {
                            System.out.println("[ColoryrASM] handshake failed: cannot compute proof.");
                            ctx.close();
                            return;
                        }
                        ctx.writeAndFlush(proof);
                        step = STEP_RESULT;
                        continue;
                    }
                    if (type != ColoryrPackBuilder.TypeGameAuthResult) {
                        System.out.println("[ColoryrASM] handshake failed: result expected, got " + type);
                        ctx.close();
                        return;
                    }
                    // 验证通过后才注册游戏通道并上报初始鼠标状态
                    step = STEP_READY;
                    ctx.writeAndFlush(ColoryrPackBuilder.buildGameUUID());
                    ctx.writeAndFlush(ColoryrPackBuilder.buildGrabbed(ColoryrInput.isGrabbed()));
                    continue;
                }
                ColoryrPackBuilder.packRead(frame);
            } catch (Throwable e) {
                e.printStackTrace();
            } finally {
                frame.release();
            }
        }

        if (buffer.readableBytes() > MAX_BUFFER_SIZE) {
            buffer.clear();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (step != STEP_READY) {
            System.out.println("[ColoryrASM] handshake not finished, connection closed.");
        }
        buffer.release();
        super.channelInactive(ctx);
    }
}
