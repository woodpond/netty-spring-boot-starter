package co.wangming.nsb.netty;

import co.wangming.nsb.command.CommandMapping;
import co.wangming.nsb.command.CommandMethod;
import co.wangming.nsb.command.CommandMethodCache;
import co.wangming.nsb.parsers.MessageParser;
import co.wangming.nsb.springboot.SpringContext;
import com.google.protobuf.GeneratedMessageV3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;

import java.util.ArrayList;
import java.util.List;

/**
 * Created By WangMing On 2019-12-07
 **/
@Slf4j
public class NettyCommandHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ctx.fireChannelRead(msg);
        if (msg instanceof NettyMessage) {
            NettyMessage nettyMessage = (NettyMessage) msg;
            dispatch(nettyMessage);
        }
    }

    public static void dispatch(NettyMessage nettyMessage) throws Exception {
        ChannelHandlerContext ctx = nettyMessage.getCtx();
        int messageId = nettyMessage.getMessageId();
        byte[] messageBytes = nettyMessage.getMessageBytes();
        CommandMethod commandMethod = CommandMethodCache.getMethodInfo(String.valueOf(messageId));
        List<MessageParser> messageParsers = commandMethod.getMessageParsers();
        String beanName = commandMethod.getBeanName();

        // 生成调用方法参数
        List paramters = getParameters(ctx, messageBytes, messageParsers);

        // 调用方法
        Object result = invoke(beanName, messageId, paramters);

        // 调用方法后可能产生应答, 将应答返回给前端
        response(ctx, result);
    }

    /**
     * 生成调用  #{@link CommandMapping} 方法的参数.
     * 目前只支持Protobuf参数和 #{@link ChannelHandlerContext}
     *
     * @param messageBytes
     * @param messageParsers
     * @return
     */
    private static List getParameters(ChannelHandlerContext ctx, byte[] messageBytes, List<MessageParser> messageParsers) throws Exception {
        List paramters = new ArrayList();

        for (MessageParser messageParser : messageParsers) {
            paramters.add(messageParser.parse(ctx, messageBytes));
        }

        return paramters;
    }

    /**
     * 调用 #{@link CommandMapping} 注解的方法
     *
     * @param paramters
     * @return
     */
    private static Object invoke(String beanName, int messageId, List paramters) {
        String proxyBeanName = beanName + "$$" + CommandProxy.class.getSimpleName() + "$$" + messageId;
        CommandProxy methodBean = null;
        try {
            methodBean = (CommandProxy) SpringContext.getBean(proxyBeanName);
        } catch (BeansException e) {
            log.error("获取bean异常:{}", proxyBeanName, e);
        }
        try {
            return methodBean.invoke(paramters);
        } catch (Exception e) {
            log.error("调用bean方法异常:\nproxyBeanName:{}\n CommandProxy:{}", proxyBeanName, methodBean.getClass().getName(), e);
            return null;
        }
    }

    /**
     * 对于 #{@link CommandMapping} 注解的方法产生的应答写回到前端去.
     * 此时需要区分写回的消息类型
     *
     * @param ctx
     * @param result
     */
    private static void response(ChannelHandlerContext ctx, Object result) {
        if (result == null) {
            return;
        }

        if (GeneratedMessageV3.class.isAssignableFrom(result.getClass())) {
            GeneratedMessageV3 generatedMessage = (GeneratedMessageV3) result;
            byte[] bytearray = generatedMessage.toByteArray();
            ByteBuf response = ByteBufAllocator.DEFAULT.heapBuffer(bytearray.length)
                    .writeByte(bytearray.length)
                    .writeBytes(bytearray);
            ctx.writeAndFlush(response).addListener(listener -> {
                if (listener.isSuccess()) {
                    log.debug("消息发送成功");
                } else {
                    log.info("消息发送失败", listener.cause());
                }
            });
        }
    }


}